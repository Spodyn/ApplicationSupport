package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.cases.CaseCreationService;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.readstate.CustomerMessageUnreadService;
import com.unifiedsupportinbox.ooo.OutOfOfficeResponder;
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
 * Provider-neutral inbound customer-message consumer. Slack root messages and thread replies use the
 * same thread identity so CaseCreationService can route activity to the current Case generation.
 */
@Service
class InboundRootMessageCommandService implements InboundMessageCommandHandler {

    private final CaseCreationService cases;
    private final CustomerMessageUnreadService unread;
    private final OutOfOfficeResponder outOfOffice;
    private final JdbcTemplate jdbc;
    private final MessageCreatedOutboxPublisher messageCreated;

    InboundRootMessageCommandService(
            CaseCreationService cases,
            CustomerMessageUnreadService unread,
            OutOfOfficeResponder outOfOffice,
            JdbcTemplate jdbc,
            MessageCreatedOutboxPublisher messageCreated) {
        this.cases = cases;
        this.unread = unread;
        this.outOfOffice = outOfOffice;
        this.jdbc = jdbc;
        this.messageCreated = messageCreated;
    }

    @Override
    @Transactional
    public Result handle(Command command) {
        Objects.requireNonNull(command, "command");
        validateInboundCreate(command);

        CaseCreationService.Result caseResult = cases.create(new CaseCreationService.Command(
                command.inboundEventId(),
                command.integrationId(),
                command.channelId(),
                command.provider(),
                command.externalChannelId(),
                command.externalThreadKey(),
                command.correlationId()));

        List<UUID> insertedMessageIds = jdbc.query("""
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
                RETURNING id
                """,
                preparedStatement -> {
                    preparedStatement.setObject(1, caseResult.caseId());
                    preparedStatement.setString(2, command.externalMessageId());
                    preparedStatement.setString(3, command.externalThreadKey());
                    preparedStatement.setString(4, command.authorExternalId());
                    preparedStatement.setString(5, command.body());
                    preparedStatement.setObject(6, utc(command.providerOccurredAt()));
                    preparedStatement.setString(7, command.correlationId());
                },
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));

        if (insertedMessageIds.isEmpty()) {
            UUID existingMessageId = existingMessageId(caseResult.caseId(), command.externalMessageId());
            if (existingMessageId == null) {
                throw new IllegalStateException("Inbound Message insert conflicted but no persisted Message exists.");
            }
            return new Result(existingMessageId, caseResult.caseId(), false);
        }

        if (insertedMessageIds.size() != 1) {
            throw new IllegalStateException("Inbound Message insert returned an unexpected number of rows.");
        }

        UUID messageId = insertedMessageIds.getFirst();
        messageCreated.publish(messageId, caseResult.caseId(), command.correlationId());
        unread.customerMessageCreated(caseResult.caseId(), messageId, command.correlationId());
        outOfOffice.customerMessageReceived(caseResult.caseId(), null, command.correlationId());
        return new Result(messageId, caseResult.caseId(), true);
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
        requiredText(command.externalThreadKey(), "externalThreadKey", 255);
        requiredText(command.externalChannelId(), "externalChannelId", 255);
        requiredText(command.authorExternalId(), "authorExternalId", 255);
        Objects.requireNonNull(command.body(), "body");
        Objects.requireNonNull(command.providerOccurredAt(), "providerOccurredAt");
        requiredText(command.correlationId(), "correlationId", 128);

        if (command.mutation() != Mutation.CREATE) {
            throw notReady("Inbound message mutations are handled by a later messaging task.");
        }
        if (provider != IntegrationProvider.SLACK && provider != IntegrationProvider.TELEGRAM) {
            throw notReady("Only Slack and Telegram inbound message mapping is active in the current phase.");
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
