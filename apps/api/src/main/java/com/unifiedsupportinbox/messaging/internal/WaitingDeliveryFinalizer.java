package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.sla.CaseOperationalDeadlines;
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

    private static final String CASE_UPDATED = "case.updated";

    private final JdbcTemplate jdbc;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;
    private final CaseOperationalDeadlines deadlines;

    WaitingDeliveryFinalizer(
            JdbcTemplate jdbc,
            AuditEventStore audit,
            OutboxEventStore outbox,
            ObjectMapper json, CaseOperationalDeadlines deadlines) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
        this.deadlines = deadlines;
    }

    boolean finalizeWaiting(MessageDeliveryRepository.DeliveryMessageRecord message, Instant sentAt) {
        Long waitingSeconds = message.askWaitingSeconds();
        Long replyVersion = message.replyWaitCaseVersion();
        if (waitingSeconds == null && replyVersion == null) return false;
        Instant waitingUntil = sentAt.plusSeconds(waitingSeconds == null ? 60L * deadlines.customerWaitingMinutes() : waitingSeconds);
        UUID owner = message.authorUserId();
        // Replies queued together in OPEN may share the version before the first
        // acceptance. A customer reply or ownership change advances past that version.
        Transition transition = jdbc.query("""
                UPDATE cases
                SET status = 'WAITING_FOR_CUSTOMER',
                    waiting_until = ?,
                    updated_at = statement_timestamp(),
                    last_activity_at = statement_timestamp(),
                    version = CASE WHEN status = 'VERIFICATION' THEN version + 1 ELSE version END
                WHERE id = ?
                  AND owner_user_id = ?
                  AND ((status = 'VERIFICATION' AND
                        (version = ?::bigint OR (?::bigint IS NULL AND updated_at <= ?::timestamptz)))
                       OR (status = 'WAITING_FOR_CUSTOMER' AND version IN (?::bigint, ?::bigint + 1)))
                  AND NOT EXISTS (
                      SELECT 1 FROM messages inbound
                      WHERE inbound.case_id = cases.id AND inbound.inbound
                        AND inbound.created_at >= ?::timestamptz
                  )
                RETURNING new.version, old.status = 'VERIFICATION' AS entered
                """, (rs, rowNum) -> new Transition(rs.getLong("version"), rs.getBoolean("entered")),
                OffsetDateTime.ofInstant(waitingUntil, ZoneOffset.UTC),
                message.caseId(), owner, replyVersion, replyVersion,
                OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC), replyVersion, replyVersion,
                OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC)).stream().findFirst().orElse(null);

        if (transition == null) {
            // Provider acceptance is already durable. Do not throw and accidentally
            // retry the provider post if an admin/state race made WAITING inapplicable.
            return false;
        }
        deadlines.followUp(message.caseId(), message.messageId(), sentAt, waitingUntil);
        if (!transition.entered()) return false;

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

    private record Transition(long version, boolean entered) {}
}
