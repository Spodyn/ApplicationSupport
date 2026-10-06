package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.sla.CaseSlaWaitingRecorder;
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
 * Finalizes Ask Customer only after the provider accepted the outbound Message.
 * A delivery-state race never causes a second provider send: the Message success
 * remains authoritative even if the Case can no longer enter WAITING.
 */
@Service
class AskCustomerDeliveryFinalizer {

    private static final String CASE_UPDATED = "case.updated";

    private final JdbcTemplate jdbc;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;
    private final CaseSlaWaitingRecorder sla;

    AskCustomerDeliveryFinalizer(
            JdbcTemplate jdbc,
            AuditEventStore audit,
            OutboxEventStore outbox,
            ObjectMapper json,
            CaseSlaWaitingRecorder sla) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
        this.sla = sla;
    }

    void finalizeIfAsk(MessageDeliveryRepository.DeliveryMessageRecord message, Instant sentAt) {
        Long waitingSeconds = message.askWaitingSeconds();
        if (waitingSeconds == null) return;

        Instant waitingUntil = sentAt.plusSeconds(waitingSeconds);
        Transition transition = jdbc.query("""
                UPDATE cases
                SET status = 'WAITING_FOR_CUSTOMER',
                    owner_user_id = NULL,
                    waiting_until = ?,
                    updated_at = statement_timestamp(),
                    last_activity_at = statement_timestamp(),
                    version = version + 1
                WHERE id = ?
                  AND status = 'VERIFICATION'
                  AND owner_user_id = ?
                RETURNING version
                """, (rs, rowNum) -> new Transition(rs.getLong("version")),
                OffsetDateTime.ofInstant(waitingUntil, ZoneOffset.UTC),
                message.caseId(),
                message.authorUserId()).stream().findFirst().orElse(null);

        if (transition == null) {
            // Provider acceptance is already durable. Do not throw and accidentally
            // retry the provider post if an admin/state race made WAITING inapplicable.
            return;
        }

        sla.pauseForWaiting(message.caseId(), sentAt);

        UserRole role = jdbc.queryForObject(
                "SELECT role FROM users WHERE id = ?",
                (rs, rowNum) -> UserRole.valueOf(rs.getString("role")),
                message.authorUserId());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", "ASK_CUSTOMER");
        metadata.put("status", "WAITING_FOR_CUSTOMER");
        metadata.put("messageId", message.messageId().toString());
        metadata.put("waitingUntil", waitingUntil.toString());
        metadata.put("version", transition.version());
        audit.append(
                role == UserRole.ADMIN ? AuditActorType.ADMIN : AuditActorType.USER,
                message.authorUserId(),
                "CASE_ASK_CUSTOMER",
                "CASE",
                message.caseId(),
                message.caseId(),
                Map.copyOf(metadata));

        outbox.append(
                CASE_UPDATED,
                "case",
                message.caseId(),
                payload(message.caseId(), message.messageId(), waitingUntil, transition.version()),
                message.correlationId());
    }

    private String payload(UUID caseId, UUID messageId, Instant waitingUntil, long version) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("status", "WAITING_FOR_CUSTOMER");
        payload.putNull("ownerUserId");
        payload.put("messageId", messageId.toString());
        payload.put("waitingUntil", waitingUntil.toString());
        payload.put("version", version);
        return payload.toString();
    }

    private record Transition(long version) {}
}
