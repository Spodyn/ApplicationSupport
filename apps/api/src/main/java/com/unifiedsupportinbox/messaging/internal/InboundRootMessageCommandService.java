package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.cases.CaseCreationService;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider-neutral first-message consumer. USI-131 activates Slack root-message ingestion while
 * deliberately leaving thread replies and mutations retryable for their dedicated follow-up tasks.
 */
@Service
class InboundRootMessageCommandService implements InboundMessageCommandHandler {

    private final CaseCreationService cases;
    private final JdbcTemplate jdbc;

    InboundRootMessageCommandService(CaseCreationService cases, JdbcTemplate jdbc) {
        this.cases = cases;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void handle(Command command) {
        Objects.requireNonNull(command, "command");
        validateRootCreate(command);

        CaseCreationService.Result caseResult = cases.create(new CaseCreationService.Command(
                command.inboundEventId(),
                command.integrationId(),
                command.channelId(),
                command.provider(),
                command.externalChannelId(),
                command.externalThreadKey(),
                command.correlationId()));

        int inserted = jdbc.update("""
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
                    correlation_id
                )
                VALUES (?, ?, ?, 'CUSTOMER', NULL, ?, NULL, ?, 'PLAIN_TEXT', TRUE, NULL, ?, ?)
                ON CONFLICT (case_id, external_message_id)
                    WHERE external_message_id IS NOT NULL
                DO NOTHING
                """,
                caseResult.caseId(),
                command.externalMessageId(),
                command.externalThreadKey(),
                command.authorExternalId(),
                command.body(),
                utc(command.providerOccurredAt()),
                command.correlationId());

        if (inserted == 0 && !messageExists(caseResult.caseId(), command.externalMessageId())) {
            throw new IllegalStateException("Inbound Message insert conflicted but no persisted Message exists.");
        }
    }

    private boolean messageExists(UUID caseId, String externalMessageId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE case_id = ? AND external_message_id = ?",
                Integer.class,
                caseId,
                externalMessageId);
        return count != null && count == 1;
    }

    private static void validateRootCreate(Command command) {
        Objects.requireNonNull(command.inboundEventId(), "inboundEventId");
        Objects.requireNonNull(command.integrationId(), "integrationId");
        Objects.requireNonNull(command.channelId(), "channelId");
        IntegrationProvider provider = Objects.requireNonNull(command.provider(), "provider");
        String externalMessageId = requiredText(command.externalMessageId(), "externalMessageId", 255);
        String externalThreadKey = requiredText(command.externalThreadKey(), "externalThreadKey", 255);
        requiredText(command.externalChannelId(), "externalChannelId", 255);
        requiredText(command.authorExternalId(), "authorExternalId", 255);
        Objects.requireNonNull(command.body(), "body");
        Objects.requireNonNull(command.providerOccurredAt(), "providerOccurredAt");
        requiredText(command.correlationId(), "correlationId", 128);

        if (command.mutation() != Mutation.CREATE) {
            throw notReady("Inbound message mutations are handled by a later messaging task.");
        }
        if (provider != IntegrationProvider.SLACK) {
            throw notReady("Only Slack root-message mapping is active in the current Slack-first phase.");
        }
        if (!externalMessageId.equals(externalThreadKey)) {
            throw notReady("Inbound thread replies are handled by USI-132.");
        }
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

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
