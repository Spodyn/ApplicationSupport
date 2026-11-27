package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleCatalog;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import com.unifiedsupportinbox.sla.BusinessTimeCalculator;
import com.unifiedsupportinbox.sla.CaseOperationalDeadlines;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class OperationalDeadlineService implements CaseOperationalDeadlines {
    private final JdbcTemplate jdbc;
    private final SlaPolicyRepository policies;
    private final BusinessHoursScheduleCatalog schedules;
    private final ObjectMapper json;
    private final OutboxEventStore outbox;
    private final BusinessTimeCalculator businessTime = new BusinessTimeCalculator();

    OperationalDeadlineService(JdbcTemplate jdbc, SlaPolicyRepository policies,
            BusinessHoursScheduleCatalog schedules, ObjectMapper json, OutboxEventStore outbox) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.schedules = schedules;
        this.json = json;
        this.outbox = outbox;
    }

    @Override
    public int customerWaitingMinutes() {
        return policies.active().customerWaitingMinutes();
    }

    void customerMessage(UUID caseId, UUID messageId, Instant receivedAt) {
        var policy = jdbc.query("""
                SELECT s.response_schedule::text, s.operational_first_response_minutes, s.next_response_minutes,
                       s.first_response_started_at, s.first_response_completed_at IS NULL AS before_first_response,
                       NOT EXISTS (SELECT 1 FROM case_operational_deadlines d WHERE d.case_id = c.id) AS first_deadline
                FROM cases c JOIN case_sla s ON s.case_id = c.id
                WHERE c.id = ? AND c.status NOT IN ('RESOLVED', 'IGNORED')
                """, (rs, row) -> new ResponsePolicy(rs.getString(1), rs.getObject(2, Integer.class),
                rs.getObject(3, Integer.class), rs.getObject(4, OffsetDateTime.class).toInstant(),
                rs.getBoolean(5), rs.getBoolean(6)), caseId).stream().findFirst().orElse(null);
        if (policy == null || alreadyRecorded(messageId)) return;
        var schedule = policy.schedule() == null
                ? schedules.findActiveSchedule().orElseThrow()
                : json.readValue(policy.schedule(), BusinessHoursScheduleView.class);
        String kind = policy.beforeFirstResponse() ? "FRT" : "NRT";
        Integer snapshottedMinutes = policy.beforeFirstResponse() ? policy.firstMinutes() : policy.nextMinutes();
        long minutes;
        if (snapshottedMinutes == null) {
            var configured = policies.active();
            minutes = policy.beforeFirstResponse() ? configured.firstResponseMinutes() : configured.nextResponseMinutes();
        } else {
            minutes = snapshottedMinutes;
        }
        Instant anchor = policy.beforeFirstResponse() && policy.firstDeadline() && policy.firstAt().isBefore(receivedAt)
                ? policy.firstAt() : receivedAt;
        Duration target = Duration.ofMinutes(minutes);
        finish(caseId, receivedAt, false);
        insert(caseId, messageId, kind, anchor,
                businessTime.add(schedule, anchor, target.multipliedBy(3).dividedBy(4)),
                businessTime.add(schedule, anchor, target), json.writeValueAsString(schedule));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void followUp(UUID caseId, UUID messageId, Instant acceptedAt, Instant dueAt) {
        if (alreadyRecorded(messageId)) return;
        finish(caseId, acceptedAt, true);
        insert(caseId, messageId, "FOLLOW_UP", acceptedAt, null, dueAt, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(UUID caseId, Instant time, boolean successfulResponse) {
        var at = utc(time);
        // An intake transaction can predate the message it supersedes. Preserve lock
        // order and a nonnegative lifetime even when that transaction started earlier.
        Ending ending = jdbc.query("""
                UPDATE case_operational_deadlines
                SET ended_at = GREATEST(started_at, ?),
                    breached_at = CASE WHEN kind = 'FOLLOW_UP' THEN NULL
                        WHEN due_at < ? OR (NOT ? AND due_at = ?)
                        THEN COALESCE(breached_at, due_at) ELSE breached_at END
                WHERE case_id = ? AND ended_at IS NULL
                RETURNING id, kind, due_at, reminded_at
                """, (rs, row) -> new Ending(rs.getObject("id", UUID.class), rs.getString("kind"),
                rs.getObject("due_at", OffsetDateTime.class).toInstant(), rs.getObject("reminded_at") != null),
                at, at, successfulResponse, at, caseId).stream().findFirst().orElse(null);
        if (ending == null || ending.reminded()) return;
        boolean qualifiesAtDeadline = !successfulResponse || ending.kind().equals("FOLLOW_UP");
        if (ending.dueAt().isBefore(time) || (qualifiesAtDeadline && ending.dueAt().equals(time))) {
            remind(new Due(ending.id(), caseId), time, false);
        }
    }

    @Transactional
    int remindDue(Instant now) {
        var due = jdbc.query("""
                SELECT d.id, d.case_id
                FROM case_operational_deadlines d JOIN cases c ON c.id = d.case_id
                WHERE d.ended_at IS NULL AND d.reminded_at IS NULL AND d.due_at <= ?
                  AND ((d.kind = 'FOLLOW_UP' AND c.status = 'WAITING_FOR_CUSTOMER')
                      OR (d.kind <> 'FOLLOW_UP' AND c.status IN ('NEW', 'PARTIALLY_IGNORED', 'VERIFICATION')))
                ORDER BY d.due_at, d.case_id LIMIT 100 FOR UPDATE OF c SKIP LOCKED
                """, (rs, row) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), utc(now));
        return (int) due.stream().filter(item -> remind(item, now, true)).count();
    }

    private boolean remind(Due due, Instant now, boolean requireActive) {
        int updated = jdbc.update("""
                UPDATE case_operational_deadlines
                SET reminded_at = ?, breached_at = CASE WHEN kind <> 'FOLLOW_UP' THEN due_at ELSE NULL END
                WHERE id = ? AND (NOT ? OR ended_at IS NULL) AND reminded_at IS NULL
                """, utc(now), due.id(), requireActive);
        if (updated == 0) return false;
        jdbc.update("""
                WITH eligible AS (
                    SELECT u.id FROM users u
                    WHERE u.active AND u.role IN ('USER', 'ADMIN')
                      AND (u.valid_from IS NULL OR u.valid_from <= ?)
                      AND (u.valid_until IS NULL OR u.valid_until > ?)
                      AND NOT EXISTS (SELECT 1 FROM case_ignore_votes v WHERE v.case_id = ? AND v.user_id = u.id)
                )
                INSERT INTO case_deadline_reminder_recipients (deadline_id, user_id)
                SELECT ?, e.id FROM eligible e JOIN cases c ON c.id = ?
                WHERE e.id = c.owner_user_id OR NOT EXISTS (SELECT 1 FROM eligible WHERE id = c.owner_user_id)
                ON CONFLICT DO NOTHING
                """, utc(now), utc(now), due.caseId(), due.id(), due.caseId());
        changed(due.caseId());
        return true;
    }

    private boolean alreadyRecorded(UUID messageId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM case_operational_deadlines WHERE message_id = ?)
                """, Boolean.class, messageId));
    }

    private void insert(UUID caseId, UUID messageId, String kind, Instant start, Instant warning, Instant due, String schedule) {
        jdbc.update("""
                INSERT INTO case_operational_deadlines (case_id, message_id, kind, started_at, warning_at, due_at, schedule)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, caseId, messageId, kind, utc(start), warning == null ? null : utc(warning), utc(due), schedule);
        changed(caseId);
    }

    private void changed(UUID caseId) {
        outbox.append("case.sla_changed", "case", caseId, json.createObjectNode().put("caseId", caseId.toString()).toString(),
                UUID.randomUUID().toString());
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record ResponsePolicy(String schedule, Integer firstMinutes, Integer nextMinutes, Instant firstAt,
                                  boolean beforeFirstResponse, boolean firstDeadline) {}
    private record Due(UUID id, UUID caseId) {}
    private record Ending(UUID id, String kind, Instant dueAt, boolean reminded) {}
}
