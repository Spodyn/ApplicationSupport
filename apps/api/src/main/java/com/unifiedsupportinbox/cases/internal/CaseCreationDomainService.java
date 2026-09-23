package com.unifiedsupportinbox.cases.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.cases.CaseCreationRejectedException;
import com.unifiedsupportinbox.cases.CaseCreationRejectedException.Reason;
import com.unifiedsupportinbox.cases.CaseCreationService;
import com.unifiedsupportinbox.cases.CaseStatus;
import com.unifiedsupportinbox.channel.ChannelContextLookup;
import com.unifiedsupportinbox.channel.ChannelContextLookup.Context;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class CaseCreationDomainService implements CaseCreationService {

    static final String OUTBOX_TYPE = "case.created";
    private static final String AGGREGATE_TYPE = "case";
    private static final RowMapper<PersistedCase> ROW_MAPPER = CaseCreationDomainService::mapCase;

    private final JdbcTemplate jdbc;
    private final ChannelContextLookup channels;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    CaseCreationDomainService(
            JdbcTemplate jdbc,
            ChannelContextLookup channels,
            OutboxEventStore outbox,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.channels = channels;
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    @Transactional
    public Result create(Command command) {
        Objects.requireNonNull(command, "command");
        UUID sourceInboundEventId = Objects.requireNonNull(command.sourceInboundEventId(), "sourceInboundEventId");
        UUID integrationId = Objects.requireNonNull(command.integrationId(), "integrationId");
        UUID channelId = Objects.requireNonNull(command.channelId(), "channelId");
        IntegrationProvider provider = Objects.requireNonNull(command.provider(), "provider");
        String externalConversationId = requiredText(
                command.externalConversationId(), "externalConversationId", 255);
        String externalThreadKey = optionalText(command.externalThreadKey(), "externalThreadKey", 255);
        String correlationId = requiredText(command.correlationId(), "correlationId", 128);

        Context channel = channels.findById(channelId)
                .orElseThrow(() -> rejected(Reason.CHANNEL_NOT_FOUND, "Channel mapping was not found."));
        validateChannel(channel, integrationId, provider);

        Optional<PersistedCase> existing = findActive(
                integrationId, channelId, provider, externalConversationId, externalThreadKey);
        if (existing.isPresent()) {
            return result(existing.orElseThrow(), false);
        }

        UUID relatedCaseId = findLatestTerminal(
                        integrationId, channelId, provider, externalConversationId, externalThreadKey)
                .map(PersistedCase::id)
                .orElse(null);

        List<PersistedCase> inserted = jdbc.query("""
                INSERT INTO cases (
                    customer_id,
                    integration_id,
                    channel_id,
                    provider,
                    external_conversation_id,
                    external_thread_key,
                    status,
                    related_case_id
                )
                VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?)
                ON CONFLICT DO NOTHING
                RETURNING id,
                          reference,
                          customer_id,
                          integration_id,
                          channel_id,
                          provider,
                          external_conversation_id,
                          external_thread_key,
                          status,
                          related_case_id,
                          created_at
                """, preparedStatement -> {
            preparedStatement.setObject(1, channel.customerId());
            preparedStatement.setObject(2, integrationId);
            preparedStatement.setObject(3, channelId);
            preparedStatement.setString(4, provider.name());
            preparedStatement.setString(5, externalConversationId);
            preparedStatement.setString(6, externalThreadKey);
            preparedStatement.setObject(7, relatedCaseId);
        }, ROW_MAPPER);

        if (inserted.isEmpty()) {
            PersistedCase winner = findActive(
                            integrationId, channelId, provider, externalConversationId, externalThreadKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Case insert conflicted but no active provider context exists."));
            return result(winner, false);
        }

        PersistedCase created = inserted.getFirst();
        outbox.append(
                OUTBOX_TYPE,
                AGGREGATE_TYPE,
                created.id(),
                caseCreatedPayload(created, sourceInboundEventId),
                correlationId);
        return result(created, true);
    }

    private void validateChannel(Context channel, UUID integrationId, IntegrationProvider provider) {
        if (!channel.integrationId().equals(integrationId)) {
            throw rejected(Reason.INTEGRATION_MISMATCH, "Channel does not belong to the requested integration.");
        }
        if (channel.provider() != provider) {
            throw rejected(Reason.PROVIDER_MISMATCH, "Channel provider does not match the requested provider.");
        }
        if (channel.ignored()) {
            throw rejected(Reason.CHANNEL_IGNORED, "Ignored channels cannot create Cases.");
        }
        if (!channel.active()) {
            throw rejected(Reason.CHANNEL_INACTIVE, "Inactive channels cannot create Cases.");
        }
        if (channel.customerId() == null) {
            throw rejected(Reason.CHANNEL_CUSTOMER_UNMAPPED, "Channel must be mapped to a Customer before Case creation.");
        }
    }

    private Optional<PersistedCase> findActive(
            UUID integrationId,
            UUID channelId,
            IntegrationProvider provider,
            String externalConversationId,
            String externalThreadKey) {
        return jdbc.query(selectCaseSql() + """
                WHERE integration_id = ?
                  AND channel_id = ?
                  AND provider = ?
                  AND external_conversation_id = ?
                  AND COALESCE(external_thread_key, '') = COALESCE(?, '')
                  AND status NOT IN ('IGNORED', 'RESOLVED')
                """,
                ROW_MAPPER,
                integrationId,
                channelId,
                provider.name(),
                externalConversationId,
                externalThreadKey).stream().findFirst();
    }

    private Optional<PersistedCase> findLatestTerminal(
            UUID integrationId,
            UUID channelId,
            IntegrationProvider provider,
            String externalConversationId,
            String externalThreadKey) {
        return jdbc.query(selectCaseSql() + """
                WHERE integration_id = ?
                  AND channel_id = ?
                  AND provider = ?
                  AND external_conversation_id = ?
                  AND COALESCE(external_thread_key, '') = COALESCE(?, '')
                  AND status IN ('IGNORED', 'RESOLVED')
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """,
                ROW_MAPPER,
                integrationId,
                channelId,
                provider.name(),
                externalConversationId,
                externalThreadKey).stream().findFirst();
    }

    private static String selectCaseSql() {
        return """
                SELECT id,
                       reference,
                       customer_id,
                       integration_id,
                       channel_id,
                       provider,
                       external_conversation_id,
                       external_thread_key,
                       status,
                       related_case_id,
                       created_at
                FROM cases
                """;
    }

    private String caseCreatedPayload(PersistedCase created, UUID sourceInboundEventId) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", created.id().toString());
        payload.put("reference", created.reference());
        payload.put("customerId", created.customerId().toString());
        payload.put("integrationId", created.integrationId().toString());
        payload.put("channelId", created.channelId().toString());
        payload.put("provider", created.provider().name());
        payload.put("externalConversationId", created.externalConversationId());
        if (created.externalThreadKey() == null) payload.putNull("externalThreadKey");
        else payload.put("externalThreadKey", created.externalThreadKey());
        if (created.relatedCaseId() == null) payload.putNull("relatedCaseId");
        else payload.put("relatedCaseId", created.relatedCaseId().toString());
        payload.put("sourceInboundEventId", sourceInboundEventId.toString());
        payload.put("createdAt", created.createdAt().toString());
        return payload.toString();
    }

    private static Result result(PersistedCase persisted, boolean created) {
        return new Result(
                persisted.id(),
                persisted.reference(),
                persisted.customerId(),
                persisted.integrationId(),
                persisted.channelId(),
                persisted.provider(),
                persisted.externalConversationId(),
                persisted.externalThreadKey(),
                persisted.status(),
                persisted.createdAt(),
                created);
    }

    private static PersistedCase mapCase(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PersistedCase(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("reference"),
                resultSet.getObject("customer_id", UUID.class),
                resultSet.getObject("integration_id", UUID.class),
                resultSet.getObject("channel_id", UUID.class),
                IntegrationProvider.valueOf(resultSet.getString("provider")),
                resultSet.getString("external_conversation_id"),
                resultSet.getString("external_thread_key"),
                CaseStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("related_case_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static CaseCreationRejectedException rejected(Reason reason, String message) {
        return new CaseCreationRejectedException(reason, message);
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw rejected(Reason.INVALID_PROVIDER_CONTEXT, field + " must not be blank.");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw rejected(Reason.INVALID_PROVIDER_CONTEXT, field + " exceeds max length " + maxLength + ".");
        }
        return normalized;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength) {
            throw rejected(Reason.INVALID_PROVIDER_CONTEXT, field + " exceeds max length " + maxLength + ".");
        }
        return normalized;
    }

    private record PersistedCase(
            UUID id,
            String reference,
            UUID customerId,
            UUID integrationId,
            UUID channelId,
            IntegrationProvider provider,
            String externalConversationId,
            String externalThreadKey,
            CaseStatus status,
            UUID relatedCaseId,
            Instant createdAt) {
    }
}
