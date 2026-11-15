package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.sla.CaseSlaWaitingRecorder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class WaitingTimeoutService {

    private static final String CASE_UPDATED = "case.updated";

    private final JdbcTemplate jdbc;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;
    private final CaseSlaWaitingRecorder sla;

    WaitingTimeoutService(
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
    int processDue(int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500.");
        }
        Instant now = Instant.now();
        List<DueCase> due = jdbc.query("""
                SELECT id, waiting_until, version, owner_user_id
                FROM cases
                WHERE status = 'WAITING_FOR_CUSTOMER'
                  AND waiting_until <= ?
                ORDER BY waiting_until, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """, (rs, rowNum) -> new DueCase(
                        rs.getObject("id", UUID.class),
                        rs.getObject("waiting_until", OffsetDateTime.class).toInstant(),
                        rs.getLong("version"), rs.getObject("owner_user_id", UUID.class)),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                batchSize);

        int processed = 0;
        for (DueCase item : due) {
            long nextVersion = item.version() + 1;
            String status = item.owner() == null ? "NEW" : "VERIFICATION";
            int changed = jdbc.update("""
                    UPDATE cases
                    SET status = ?,
                        waiting_until = NULL,
                        updated_at = statement_timestamp(),
                        last_activity_at = statement_timestamp(),
                        version = version + 1
                    WHERE id = ?
                      AND status = 'WAITING_FOR_CUSTOMER'
                      AND version = ?
                    """, status, item.id(), item.version());
            if (changed != 1) continue;

            sla.resumeAfterWaiting(item.id(), now);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("reason", "WAITING_TIMEOUT");
            metadata.put("previousStatus", "WAITING_FOR_CUSTOMER");
            metadata.put("status", status);
            metadata.put("waitingUntil", item.waitingUntil().toString());
            metadata.put("version", nextVersion);
            String correlationId = UUID.randomUUID().toString();
            try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
                audit.append(
                        AuditActorType.SYSTEM,
                        null,
                        "CASE_WAITING_TIMEOUT",
                        "CASE",
                        item.id(),
                        item.id(),
                        Map.copyOf(metadata));
                outbox.append(
                        CASE_UPDATED,
                        "case",
                        item.id(),
                        payload(item.id(), nextVersion, status, item.owner()),
                        correlationId);
            }
            processed++;
        }
        return processed;
    }

    private String payload(UUID caseId, long version, String status, UUID owner) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("status", status);
        if (owner == null) payload.putNull("ownerUserId");
        else payload.put("ownerUserId", owner.toString());
        payload.putNull("waitingUntil");
        payload.put("reason", "WAITING_TIMEOUT");
        payload.put("version", version);
        return payload.toString();
    }

    private record DueCase(UUID id, Instant waitingUntil, long version, UUID owner) {}
}
