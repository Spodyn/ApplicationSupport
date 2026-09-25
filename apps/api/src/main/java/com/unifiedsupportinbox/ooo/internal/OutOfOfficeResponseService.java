package com.unifiedsupportinbox.ooo.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.ooo.OutOfOfficePolicyView;
import com.unifiedsupportinbox.ooo.OutOfOfficeResponder;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleCatalog;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import com.unifiedsupportinbox.sla.BusinessTimeCalculator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Transactional OOO deduplication and durable delivery request creation. */
@Service
class OutOfOfficeResponseService implements OutOfOfficeResponder {

    private static final String OUTBOX_TYPE = "message.send_requested";
    private final OutOfOfficeRepository policies;
    private final BusinessHoursScheduleCatalog schedules;
    private final JdbcTemplate jdbc;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    OutOfOfficeResponseService(OutOfOfficeRepository policies, BusinessHoursScheduleCatalog schedules,
            JdbcTemplate jdbc, OutboxEventStore outbox, ObjectMapper json) {
        this.policies = policies;
        this.schedules = schedules;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void customerMessageReceived(UUID caseId, String customerName, String correlationId) {
        Objects.requireNonNull(caseId, "caseId");
        OutOfOfficePolicyView policy = policies.get();
        if (!policy.enabled()) return;
        BusinessHoursScheduleView schedule = schedules.findActiveSchedule()
                .orElseThrow(() -> new IllegalStateException("Active business-hours schedule is missing."));
        Instant now = Instant.now();
        BusinessTimeCalculator.Opening opening = new BusinessTimeCalculator().opening(schedule, now);
        if (opening.open() || opening.nextOpening() == null) return;

        UUID messageId = UUID.randomUUID();
        String closureKey = opening.nextOpening().toString();
        String body = OutOfOfficeService.render(policy.template(), customerName, opening.nextOpening(), schedule.timezone());
        String externalThreadKey = jdbc.query("SELECT external_thread_key FROM cases WHERE id = ?", 
                (rs, row) -> rs.getString(1), caseId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Case disappeared while creating out-of-office response."));
        jdbc.update("""
                INSERT INTO messages (id, case_id, external_message_id, external_thread_key, kind,
                    author_user_id, author_external_id, author_name, body, body_format, inbound,
                    delivery_status, provider_created_at, correlation_id)
                VALUES (?, ?, NULL, ?, 'SYSTEM', NULL, NULL, NULL, ?, 'PLAIN_TEXT', FALSE, 'QUEUED', NULL, ?)
                """, messageId, caseId, externalThreadKey, body, correlationId);
        int claimed = jdbc.update("""
                INSERT INTO ooo_deliveries (case_id, closure_key, message_id)
                VALUES (?, ?, ?)
                ON CONFLICT (case_id, closure_key) DO NOTHING
                """, caseId, closureKey, messageId);
        if (claimed == 0) {
            jdbc.update("DELETE FROM messages WHERE id = ?", messageId);
            return;
        }
        outbox.append(OUTBOX_TYPE, "message", messageId, payload(messageId, caseId, externalThreadKey), correlationId);
    }

    private String payload(UUID messageId, UUID caseId, String externalThreadKey) {
        ObjectNode payload = json.createObjectNode();
        payload.put("messageId", messageId.toString());
        payload.put("caseId", caseId.toString());
        if (externalThreadKey != null) payload.put("externalThreadKey", externalThreadKey);
        return payload.toString();
    }
}
