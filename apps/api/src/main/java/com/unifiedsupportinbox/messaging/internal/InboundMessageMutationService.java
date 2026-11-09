package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Command;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Mutation;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Result;
import com.unifiedsupportinbox.messaging.InboundMessageMutationPendingException;
import com.unifiedsupportinbox.readstate.CustomerMessageUnreadService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Mutations target the original Message across Case generations, including terminal Cases. */
@Service
class InboundMessageMutationService {
    private final JdbcTemplate jdbc;
    private final AuditEventStore audit;
    private final CustomerMessageUnreadService unread;
    private final MessageCreatedOutboxPublisher events;

    InboundMessageMutationService(JdbcTemplate jdbc, AuditEventStore audit,
            CustomerMessageUnreadService unread, MessageCreatedOutboxPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.unread = unread;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Result apply(Command command) {
        Target target = jdbc.query("""
                SELECT m.id, m.case_id
                FROM cases c JOIN messages m ON m.case_id = c.id
                JOIN channels channel ON channel.id = c.channel_id
                WHERE c.integration_id = ? AND c.channel_id = ? AND c.provider = ?
                  AND channel.external_channel_id = ?
                  AND m.external_message_id = ? AND m.external_thread_key = ?
                  AND m.kind = 'CUSTOMER' AND m.inbound = TRUE
                  AND m.author_external_id = ?
                ORDER BY m.created_at DESC, m.id DESC
                LIMIT 1 FOR UPDATE OF c, m
                """, (rs, row) -> new Target(rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class)),
                command.integrationId(), command.channelId(), command.provider().name(), command.externalChannelId(),
                command.externalMessageId(), command.externalThreadKey(), command.authorExternalId())
                .stream().findFirst().orElseThrow(InboundMessageMutationPendingException::new);
        OffsetDateTime occurredAt = OffsetDateTime.ofInstant(command.providerOccurredAt(), ZoneOffset.UTC);
        int changed;
        if (command.mutation() == Mutation.EDIT) {
            changed = jdbc.update("""
                    UPDATE messages SET body = ?, edited_at = ?
                    WHERE id = ? AND deleted_at IS NULL
                      AND (edited_at IS NULL OR edited_at < ?)
                    """, command.body(), occurredAt, target.messageId(), occurredAt);
        } else {
            changed = jdbc.update("""
                    UPDATE messages SET body = '', deleted_at = ?
                    WHERE id = ? AND deleted_at IS NULL
                    """, occurredAt, target.messageId());
        }
        if (changed == 0) return new Result(target.messageId(), target.caseId(), false);
        jdbc.update("""
                UPDATE cases SET updated_at = CURRENT_TIMESTAMP, last_activity_at = CURRENT_TIMESTAMP,
                                 version = version + 1 WHERE id = ?
                """, target.caseId());
        audit.append(AuditActorType.PROVIDER, null,
                command.mutation() == Mutation.EDIT ? "CUSTOMER_MESSAGE_EDITED" : "CUSTOMER_MESSAGE_DELETED",
                "message", target.messageId(), target.caseId(),
                Map.of("messageId", target.messageId().toString(), "provider", command.provider().name()));
        if (command.mutation() == Mutation.EDIT) {
            unread.customerMessageEdited(target.caseId(), target.messageId(), command.correlationId());
        }
        events.publishUpdated(target.messageId(), target.caseId(), command.correlationId());
        return new Result(target.messageId(), target.caseId(), false);
    }

    private record Target(UUID messageId, UUID caseId) {}
}
