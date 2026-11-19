package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.cases.CaseCreationService;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.readstate.CustomerMessageUnreadService;
import com.unifiedsupportinbox.ooo.OutOfOfficeResponder;
import com.unifiedsupportinbox.sla.CaseResponseCycles;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider-neutral inbound customer-message consumer. Provider adapters supply their canonical
 * conversation/thread identity so CaseCreationService can route activity to the current Case
 * generation. A null thread key represents single-active-Case contexts such as Telegram chats
 * without forum topics.
 */
@Service
class InboundRootMessageCommandService implements InboundMessageCommandHandler {

    private final CaseCreationService cases;
    private final InboundMessageMutationService mutations;
    private final CustomerMessageUnreadService unread;
    private final OutOfOfficeResponder outOfOffice;
    private final CustomerReplyWaitingService waitingReplies;
    private final JdbcTemplate jdbc;
    private final MessageCreatedOutboxPublisher messageCreated;
    private final CaseResponseCycles responseCycles;

    InboundRootMessageCommandService(
            CaseCreationService cases,
            InboundMessageMutationService mutations,
            CustomerMessageUnreadService unread,
            OutOfOfficeResponder outOfOffice,
            CustomerReplyWaitingService waitingReplies,
            JdbcTemplate jdbc,
            MessageCreatedOutboxPublisher messageCreated, CaseResponseCycles responseCycles) {
        this.cases = cases;
        this.mutations = mutations;
        this.unread = unread;
        this.outOfOffice = outOfOffice;
        this.waitingReplies = waitingReplies;
        this.jdbc = jdbc;
        this.messageCreated = messageCreated;
        this.responseCycles = responseCycles;
    }

    @Override
    @Transactional
    public Result handle(Command command) {
        Objects.requireNonNull(command, "command");
        validateInboundCreate(command);
        if (command.mutation() != Mutation.CREATE) return mutations.apply(command);

        if (command.provider() == IntegrationProvider.SLACK || command.provider() == IntegrationProvider.TEAMS) {
            Result existing = findSlackMessage(command);
            if (existing != null) return existing;
        }

        CaseCreationService.Result caseResult = cases.create(new CaseCreationService.Command(
                command.inboundEventId(),
                command.integrationId(),
                command.channelId(),
                command.provider(),
                command.externalChannelId(),
                command.externalThreadKey(),
                command.correlationId(), command.providerOccurredAt()));

        List<PersistedMessage> insertedMessages = jdbc.query("""
                INSERT INTO messages (
                    case_id,
                    external_message_id,
                    external_thread_key,
                    kind,
                    author_user_id,
                    author_external_id,
                    author_name,
                    body,
                    body_format,
                    inbound,
                    delivery_status,
                    provider_created_at,
                    created_at,
                    correlation_id
                )
                VALUES (?, ?, ?, 'CUSTOMER', NULL, ?, ?, ?, 'PLAIN_TEXT', TRUE, NULL, ?, statement_timestamp(), ?)
                ON CONFLICT (case_id, external_message_id)
                    WHERE external_message_id IS NOT NULL
                DO NOTHING
                RETURNING id, created_at
                """,
                preparedStatement -> {
                    preparedStatement.setObject(1, caseResult.caseId());
                    preparedStatement.setString(2, command.externalMessageId());
                    preparedStatement.setString(3, command.externalThreadKey());
                    preparedStatement.setString(4, command.authorExternalId());
                    preparedStatement.setString(5, command.authorName());
                    preparedStatement.setString(6, command.body());
                    preparedStatement.setObject(7, utc(command.providerOccurredAt()));
                    preparedStatement.setString(8, command.correlationId());
                },
                (resultSet, rowNumber) -> new PersistedMessage(resultSet.getObject("id", UUID.class),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()));

        if (insertedMessages.isEmpty()) {
            UUID existingMessageId = existingMessageId(caseResult.caseId(), command.externalMessageId());
            if (existingMessageId == null) {
                throw new IllegalStateException("Inbound Message insert conflicted but no persisted Message exists.");
            }
            return new Result(existingMessageId, caseResult.caseId(), false);
        }

        if (insertedMessages.size() != 1) {
            throw new IllegalStateException("Inbound Message insert returned an unexpected number of rows.");
        }

        PersistedMessage message = insertedMessages.getFirst();
        UUID messageId = message.id();
        waitingReplies.customerReplied(
                caseResult.caseId(),
                messageId,
                message.receivedAt(),
                command.correlationId());
        responseCycles.customerMessage(caseResult.caseId(), messageId, message.receivedAt());
        messageCreated.publish(messageId, caseResult.caseId(), command.correlationId());
        unread.customerMessageCreated(caseResult.caseId(), messageId, command.correlationId());
        outOfOffice.customerMessageReceived(caseResult.caseId(), null, command.correlationId());
        return new Result(messageId, caseResult.caseId(), true);
    }

    private Result findSlackMessage(Command command) {
        // Serialize equivalent callbacks even if their Case is resolved between deliveries.
        String identity = command.integrationId() + ":" + command.channelId() + ":" + command.externalMessageId();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, identity);
        return jdbc.query("""
                SELECT m.id, m.case_id
                FROM cases c JOIN messages m ON m.case_id = c.id
                WHERE c.integration_id = ? AND c.channel_id = ? AND m.external_message_id = ?
                  AND m.inbound AND m.kind = 'CUSTOMER'
                ORDER BY m.created_at, m.id
                LIMIT 1
                """, (rs, row) -> new Result(rs.getObject("id", UUID.class),
                rs.getObject("case_id", UUID.class), false),
                command.integrationId(), command.channelId(), command.externalMessageId()).stream().findFirst().orElse(null);
    }

    private UUID existingMessageId(UUID caseId, String externalMessageId) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM messages WHERE case_id = ? AND external_message_id = ?",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                caseId,
                externalMessageId);
        if (ids.size() > 1) {
            throw new IllegalStateException("Multiple persisted Messages share the same provider identity.");
        }
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static void validateInboundCreate(Command command) {
        Objects.requireNonNull(command.inboundEventId(), "inboundEventId");
        Objects.requireNonNull(command.integrationId(), "integrationId");
        Objects.requireNonNull(command.channelId(), "channelId");
        IntegrationProvider provider = Objects.requireNonNull(command.provider(), "provider");
        requiredText(command.externalMessageId(), "externalMessageId", 255);
        if (provider == IntegrationProvider.SLACK || command.externalThreadKey() != null) {
            requiredText(command.externalThreadKey(), "externalThreadKey", 255);
        }
        requiredText(command.externalChannelId(), "externalChannelId", 255);
        requiredText(command.authorExternalId(), "authorExternalId", 255);
        if (command.mutation() != Mutation.DELETE) Objects.requireNonNull(command.body(), "body");
        Objects.requireNonNull(command.providerOccurredAt(), "providerOccurredAt");
        requiredText(command.correlationId(), "correlationId", 128);

        Objects.requireNonNull(command.mutation(), "mutation");
    }

    private static IllegalStateException notReady(String message) {
        return new IllegalStateException(message);
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength + ".");
        }
        return normalized;
    }

    private record PersistedMessage(UUID id, Instant receivedAt) {}

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
