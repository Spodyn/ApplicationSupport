package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.sla.CaseSlaWaitingRecorder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies the WAITING_FOR_CUSTOMER -> NEW transition after a newly persisted
 * inbound customer Message. The caller owns the surrounding Message transaction.
 */
@Service
class CustomerReplyWaitingService {

    private static final String CASE_UPDATED = "case.updated";

    private final JdbcTemplate jdbc;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;
    private final CaseSlaWaitingRecorder sla;

    CustomerReplyWaitingService(
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

    @Transactional
    boolean customerReplied(
            UUID caseId,
            UUID messageId,
            Instant providerOccurredAt,
            String correlationId) {
        Long version = jdbc.query("""
                UPDATE cases
                SET status = 'NEW',
                    owner_user_id = NULL,
                    waiting_until = NULL,
                    updated_at = statement_timestamp(),
                    last_activity_at = statement_timestamp(),
                    version = version + 1
                WHERE id = ?
                  AND status = 'WAITING_FOR_CUSTOMER'
                  AND waiting_until IS NOT NULL
                RETURNING version
                """, (rs, rowNum) -> rs.getLong("version"), caseId)
                .stream().findFirst().orElse(null);

        if (version == null) return false;

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
                        "status", "NEW",
                        "messageId", messageId.toString(),
                        "version", version));

        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("status", "NEW");
        payload.putNull("ownerUserId");
        payload.putNull("waitingUntil");
        payload.put("messageId", messageId.toString());
        payload.put("version", version);
        outbox.append(
                CASE_UPDATED,
                "case",
                caseId,
                payload.toString(),
                correlationId);
        return true;
    }
}
