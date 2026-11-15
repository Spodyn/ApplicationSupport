package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.sla.CaseSlaWaitingRecorder;
import com.unifiedsupportinbox.readstate.CaseSnoozeWakeService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resumes the owned or unassigned workflow after a newly persisted
 * inbound customer Message. The caller owns the surrounding Message transaction.
 */
@Service
class CustomerReplyWaitingService {

    private static final String CASE_UPDATED = "case.updated";

    private final JdbcTemplate jdbc;
    private final CaseSnoozeWakeService snoozes;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;
    private final CaseSlaWaitingRecorder sla;

    CustomerReplyWaitingService(
            JdbcTemplate jdbc,
            CaseSnoozeWakeService snoozes,
            AuditEventStore audit,
            OutboxEventStore outbox,
            ObjectMapper json,
            CaseSlaWaitingRecorder sla) {
        this.jdbc = jdbc;
        this.snoozes = snoozes;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
        this.sla = sla;
    }

    @Transactional
    boolean customerReplied(
            UUID caseId,
            UUID messageId,
            Instant providerOccurredAt,
            String correlationId) {
        Resumed resumed = jdbc.query("""
                UPDATE cases
                SET status = CASE WHEN owner_user_id IS NULL THEN 'NEW' ELSE 'VERIFICATION' END,
                    waiting_until = NULL,
                    updated_at = statement_timestamp(),
                    last_activity_at = statement_timestamp(),
                    version = version + 1
                WHERE id = ?
                  AND status = 'WAITING_FOR_CUSTOMER'
                  AND waiting_until IS NOT NULL
                RETURNING version, status, owner_user_id
                """, (rs, rowNum) -> new Resumed(rs.getLong("version"), rs.getString("status"),
                        rs.getObject("owner_user_id", UUID.class)),
                caseId)
                .stream().findFirst().orElse(null);

        snoozes.wake(caseId);
        if (resumed == null) {
            Long resetVersion = jdbc.query("""
                    UPDATE cases
                    SET status = 'NEW',
                        updated_at = statement_timestamp(),
                        last_activity_at = statement_timestamp(),
                        version = version + 1
                    WHERE id = ? AND status = 'PARTIALLY_IGNORED'
                    RETURNING version
                    """, (rs, rowNum) -> rs.getLong("version"), caseId)
                    .stream().findFirst().orElse(null);
            if (resetVersion == null) return false;
            jdbc.update("""
                    UPDATE case_ignore_votes
                    SET active = FALSE, deactivated_at = statement_timestamp()
                    WHERE case_id = ? AND active
                    """, caseId);
            audit.append(
                    AuditActorType.PROVIDER,
                    null,
                    "CASE_CUSTOMER_REPLY",
                    "CASE",
                    caseId,
                    caseId,
                    Map.of(
                            "previousStatus", "PARTIALLY_IGNORED",
                            "status", "NEW",
                            "messageId", messageId.toString(),
                            "version", resetVersion));
            outbox.append(CASE_UPDATED, "case", caseId,
                    payload(caseId, messageId, new Resumed(resetVersion, "NEW", null)), correlationId);
            return true;
        }

        sla.resumeAfterWaiting(caseId, providerOccurredAt);
        audit.append(
                AuditActorType.PROVIDER,
                null,
                "CASE_CUSTOMER_REPLY",
                "CASE",
                caseId,
                caseId,
                Map.of(
                        "previousStatus", "WAITING_FOR_CUSTOMER",
                        "status", resumed.status(),
                        "messageId", messageId.toString(),
                        "version", resumed.version()));

        outbox.append(
                CASE_UPDATED,
                "case",
                caseId,
                payload(caseId, messageId, resumed),
                correlationId);
        return true;
    }

    private String payload(UUID caseId, UUID messageId, Resumed resumed) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("status", resumed.status());
        if (resumed.owner() == null) payload.putNull("ownerUserId");
        else payload.put("ownerUserId", resumed.owner().toString());
        payload.putNull("waitingUntil");
        payload.put("messageId", messageId.toString());
        payload.put("version", resumed.version());
        return payload.toString();
    }
    private record Resumed(long version, String status, UUID owner) {}
}
