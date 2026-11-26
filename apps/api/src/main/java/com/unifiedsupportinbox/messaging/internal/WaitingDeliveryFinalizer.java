package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.identity.UserRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Enters customer waiting only after the provider accepted the outbound Message.
 * A delivery-state race never causes a second provider send: the Message success
 * remains authoritative even if the Case can no longer enter WAITING.
 */
@Service
class WaitingDeliveryFinalizer {

    private static final long REPLY_WAIT_SECONDS = 24 * 60 * 60;
    private static final String CASE_UPDATED = "case.updated";

    private final JdbcTemplate jdbc;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    WaitingDeliveryFinalizer(
            JdbcTemplate jdbc,
            AuditEventStore audit,
            OutboxEventStore outbox,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
    }

    boolean finalizeWaiting(MessageDeliveryRepository.DeliveryMessageRecord message, Instant sentAt) {
        Long waitingSeconds = message.askWaitingSeconds();
        Long replyVersion = message.replyWaitCaseVersion();
        if (waitingSeconds == null && replyVersion == null) return false;
        Instant waitingUntil = sentAt.plusSeconds(waitingSeconds == null ? REPLY_WAIT_SECONDS : waitingSeconds);
        UUID owner = message.authorUserId();
        // The Case row lock serializes this update with intake. A further success
        // while already waiting needs no transition or deadline reset.
        Transition transition = jdbc.query("""
                UPDATE cases
                SET status = 'WAITING_FOR_CUSTOMER',
                    waiting_until = ?,
                    updated_at = statement_timestamp(),
                    last_activity_at = statement_timestamp(),
                    version = version + 1
                WHERE id = ?
                  AND status = 'VERIFICATION'
                  AND owner_user_id = ?
                  AND (version = ?::bigint OR (?::bigint IS NULL AND updated_at <= ?::timestamptz))
                  AND NOT EXISTS (
                      SELECT 1 FROM messages inbound
                      WHERE inbound.case_id = cases.id AND inbound.inbound
                        AND inbound.created_at >= ?::timestamptz
                  )
                RETURNING version
                """, (rs, rowNum) -> new Transition(rs.getLong("version")),
                OffsetDateTime.ofInstant(waitingUntil, ZoneOffset.UTC),
                message.caseId(), owner, replyVersion, replyVersion,
                OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC)).stream().findFirst().orElse(null);

        if (transition == null) {
            // Provider acceptance is already durable. Do not throw and accidentally
            // retry the provider post if an admin/state race made WAITING inapplicable.
            return false;
        }

        UserRole role = jdbc.queryForObject(
                "SELECT role FROM users WHERE id = ?",
                (rs, rowNum) -> UserRole.valueOf(rs.getString("role")),
                owner);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", "REPLY_WAITING");
        metadata.put("ownerUserId", owner.toString());
        metadata.put("status", "WAITING_FOR_CUSTOMER");
        metadata.put("messageId", message.messageId().toString());
        metadata.put("waitingUntil", waitingUntil.toString());
        metadata.put("version", transition.version());
        audit.append(
                role == UserRole.ADMIN ? AuditActorType.ADMIN : AuditActorType.USER,
                owner,
                "CASE_REPLY_WAITING",
                "CASE",
                message.caseId(),
                message.caseId(),
                Map.copyOf(metadata));

        outbox.append(
                CASE_UPDATED,
                "case",
                message.caseId(),
                payload(message.caseId(), message.messageId(), waitingUntil, owner, transition.version()),
                message.correlationId());
        return true;
    }

    private String payload(UUID caseId, UUID messageId, Instant waitingUntil, UUID owner, long version) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("status", "WAITING_FOR_CUSTOMER");
        payload.put("ownerUserId", owner.toString());
        payload.put("messageId", messageId.toString());
        payload.put("waitingUntil", waitingUntil.toString());
        payload.put("version", version);
        return payload.toString();
    }

    private record Transition(long version) {}
}
