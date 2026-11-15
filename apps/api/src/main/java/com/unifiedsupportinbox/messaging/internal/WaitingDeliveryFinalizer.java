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
        boolean ask = waitingSeconds != null;
        Instant waitingUntil = sentAt.plusSeconds(ask ? waitingSeconds : REPLY_WAIT_SECONDS);
        UUID retainedOwner = ask ? null : message.authorUserId();
        String action = ask ? "ASK_CUSTOMER" : "REPLY_WAITING";
        // MessageDeliveryService holds the shared Case row lock with intake.
        Transition transition = jdbc.query("""
                UPDATE cases
                SET status = 'WAITING_FOR_CUSTOMER',
                    owner_user_id = ?,
                    waiting_until = ?,
                    updated_at = statement_timestamp(),
                    last_activity_at = statement_timestamp(),
                    version = version + 1
                WHERE id = ?
                  AND status = 'VERIFICATION'
                  AND owner_user_id = ?
                  AND (?::bigint IS NULL OR version = ?::bigint)
                  AND NOT EXISTS (
                      SELECT 1 FROM messages inbound
                      WHERE inbound.case_id = cases.id AND inbound.inbound
                        AND inbound.created_at >= ?::timestamptz
                  )
                RETURNING version
                """, (rs, rowNum) -> new Transition(rs.getLong("version")),
                retainedOwner, OffsetDateTime.ofInstant(waitingUntil, ZoneOffset.UTC),
                message.caseId(), message.authorUserId(), replyVersion, replyVersion,
                OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC)).stream().findFirst().orElse(null);

        if (transition == null) {
            // Provider acceptance is already durable. Do not throw and accidentally
            // retry the provider post if an admin/state race made WAITING inapplicable.
            return false;
        }

        UserRole role = jdbc.queryForObject(
                "SELECT role FROM users WHERE id = ?",
                (rs, rowNum) -> UserRole.valueOf(rs.getString("role")),
                message.authorUserId());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", action);
        if (retainedOwner != null) metadata.put("ownerUserId", retainedOwner.toString());
        metadata.put("status", "WAITING_FOR_CUSTOMER");
        metadata.put("messageId", message.messageId().toString());
        metadata.put("waitingUntil", waitingUntil.toString());
        metadata.put("version", transition.version());
        audit.append(
                role == UserRole.ADMIN ? AuditActorType.ADMIN : AuditActorType.USER,
                message.authorUserId(),
                "CASE_" + action,
                "CASE",
                message.caseId(),
                message.caseId(),
                Map.copyOf(metadata));

        outbox.append(
                CASE_UPDATED,
                "case",
                message.caseId(),
                payload(message.caseId(), message.messageId(), waitingUntil, retainedOwner, transition.version()),
                message.correlationId());
        return true;
    }

    private String payload(UUID caseId, UUID messageId, Instant waitingUntil, UUID owner, long version) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("status", "WAITING_FOR_CUSTOMER");
        if (owner == null) payload.putNull("ownerUserId");
        else payload.put("ownerUserId", owner.toString());
        payload.put("messageId", messageId.toString());
        payload.put("waitingUntil", waitingUntil.toString());
        payload.put("version", version);
        return payload.toString();
    }

    private record Transition(long version) {}
}
