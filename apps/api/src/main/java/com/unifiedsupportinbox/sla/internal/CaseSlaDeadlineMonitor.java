package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.notification.NotificationDeliveryQueue;
import com.unifiedsupportinbox.notification.NotificationDeliveryQueue.NotificationIntent;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Advances authoritative SLA state and emits durable, deduplicated escalation intents. */
@Component
class CaseSlaDeadlineMonitor {

    private final JdbcTemplate jdbc;
    private final NotificationDeliveryQueue notifications;

    CaseSlaDeadlineMonitor(JdbcTemplate jdbc, NotificationDeliveryQueue notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${usi.sla.monitor-interval:30s}")
    @Transactional
    void advanceDueCases() {
        for (DueCase due : markBreached()) enqueue(due, "sla_breached", "critical");
        for (DueCase due : markWarning()) enqueue(due, "sla_warning", "warning");
        for (DueCase due : findDueAgingSignals()) enqueue(due, due.agingEventType(), "warning");
    }

    private List<DueCase> markBreached() {
        return jdbc.query("""
                UPDATE case_sla s SET state = 'BREACHED', updated_at = now()
                FROM cases c
                WHERE c.id = s.case_id
                  AND c.status NOT IN ('RESOLVED', 'IGNORED', 'WAITING_FOR_CUSTOMER')
                  AND s.paused_at IS NULL AND s.state <> 'BREACHED'
                  AND (
                    (s.first_response_completed_at IS NULL AND now() >= s.first_response_due_at)
                    OR (c.status IN ('NEW', 'PARTIALLY_IGNORED') AND now() >= s.unclaimed_breach_at)
                    OR (c.status = 'VERIFICATION' AND s.in_progress_breach_at IS NOT NULL AND now() >= s.in_progress_breach_at)
                  )
                RETURNING s.case_id, c.reference, NULL::text AS aging_event_type
                """, (rs, row) -> mapDue(rs));
    }

    private List<DueCase> markWarning() {
        return jdbc.query("""
                UPDATE case_sla s SET state = 'WARNING', updated_at = now()
                FROM cases c, organization_settings os
                WHERE c.id = s.case_id AND os.singleton = TRUE
                  AND c.status NOT IN ('RESOLVED', 'IGNORED', 'WAITING_FOR_CUSTOMER')
                  AND s.paused_at IS NULL AND s.state = 'ON_TRACK'
                  AND (
                    (s.first_response_completed_at IS NULL AND now() >= s.first_response_due_at - (os.sla_warning_before_minutes * INTERVAL '1 minute'))
                    OR (c.status IN ('NEW', 'PARTIALLY_IGNORED') AND now() >= s.unclaimed_warning_at)
                    OR (c.status = 'VERIFICATION' AND s.in_progress_warning_at IS NOT NULL AND now() >= s.in_progress_warning_at)
                  )
                RETURNING s.case_id, c.reference, NULL::text AS aging_event_type
                """, (rs, row) -> mapDue(rs));
    }

    private List<DueCase> findDueAgingSignals() {
        return jdbc.query("""
                SELECT s.case_id, c.reference,
                    CASE
                      WHEN c.status IN ('NEW', 'PARTIALLY_IGNORED') AND now() >= s.unclaimed_warning_at THEN 'unclaimed_too_long'
                      WHEN c.status = 'VERIFICATION' AND s.in_progress_warning_at IS NOT NULL AND now() >= s.in_progress_warning_at THEN 'in_progress_too_long'
                    END AS aging_event_type
                FROM case_sla s JOIN cases c ON c.id = s.case_id
                WHERE c.status NOT IN ('RESOLVED', 'IGNORED', 'WAITING_FOR_CUSTOMER')
                  AND s.paused_at IS NULL
                  AND (
                    (c.status IN ('NEW', 'PARTIALLY_IGNORED') AND now() >= s.unclaimed_warning_at)
                    OR (c.status = 'VERIFICATION' AND s.in_progress_warning_at IS NOT NULL AND now() >= s.in_progress_warning_at)
                  )
                """, (rs, row) -> mapDue(rs));
    }

    private void enqueue(DueCase due, String eventType, String severity) {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) correlationId = UUID.randomUUID().toString();
        notifications.enqueue(new NotificationIntent(
                eventType + ":" + due.caseId(), eventType, severity,
                "{\"caseId\":\"" + due.caseId() + "\",\"reference\":\"" + due.reference() + "\"}",
                correlationId));
    }

    private DueCase mapDue(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DueCase(rs.getObject("case_id", UUID.class), rs.getString("reference"), rs.getString("aging_event_type"));
    }

    private record DueCase(UUID caseId, String reference, String agingEventType) { }
}
