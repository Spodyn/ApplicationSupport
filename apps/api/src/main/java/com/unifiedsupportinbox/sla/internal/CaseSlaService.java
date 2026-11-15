package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.sla.CaseResponseCycles;
import com.unifiedsupportinbox.sla.CaseSlaInitializer;
import com.unifiedsupportinbox.sla.CaseSlaReopenRecorder;
import com.unifiedsupportinbox.sla.CaseSlaClaimRecorder;
import com.unifiedsupportinbox.sla.CaseSlaWaitingRecorder;
import com.unifiedsupportinbox.sla.CaseSlaResponseRecorder;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleCatalog;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import com.unifiedsupportinbox.sla.BusinessTimeCalculator;
import com.unifiedsupportinbox.sla.SlaPolicyView;
import com.unifiedsupportinbox.notification.NotificationDeliveryQueue;
import com.unifiedsupportinbox.notification.NotificationDeliveryQueue.NotificationIntent;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class CaseSlaService implements CaseSlaInitializer, CaseSlaClaimRecorder, CaseSlaWaitingRecorder, CaseSlaResponseRecorder, CaseSlaReopenRecorder, CaseResponseCycles {
    private final JdbcTemplate jdbc;
    private final SlaPolicyRepository policies;
    private final BusinessHoursScheduleCatalog schedules;
    private final NotificationDeliveryQueue notifications;
    private final ObjectMapper json;
    private final BusinessTimeCalculator businessTime = new BusinessTimeCalculator();

    CaseSlaService(
            JdbcTemplate jdbc,
            SlaPolicyRepository policies,
            BusinessHoursScheduleCatalog schedules,
            NotificationDeliveryQueue notifications, ObjectMapper json) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.schedules = schedules;
        this.notifications = notifications;
        this.json = json;
    }

    @Override @Transactional
    public void initialize(UUID caseId, Instant createdAt, Instant customerSentAt) {
        SlaPolicyView policy = policies.active();
        BusinessHoursScheduleView schedule = schedules.findActiveSchedule()
                .orElseThrow(() -> new IllegalStateException("Active business-hours schedule is missing."));
        ResponseDeadline first = responseDeadline(schedule, customerSentAt, policy.firstResponseMinutes());
        Instant unclaimedWarning = dueAt(schedule, createdAt, policy.unclaimedWarningMinutes());
        Instant unclaimedBreach = dueAt(schedule, createdAt, policy.unclaimedBreachMinutes());
        OffsetDateTime startedAt = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO case_sla (case_id, policy_id, first_response_started_at, first_response_due_at,
                    unclaimed_started_at, unclaimed_warning_at, unclaimed_breach_at, first_response_warning_at,
                    response_schedule, next_response_minutes, response_policy_version, response_pause_waiting)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) ON CONFLICT (case_id) DO NOTHING
                """, caseId, policy.id(), at(customerSentAt), at(first.due()), startedAt,
                at(unclaimedWarning), at(unclaimedBreach), at(first.warning()), json.writeValueAsString(schedule),
                policy.nextResponseMinutes(), policy.version(), policy.pauseWaiting());
    }

    private BusinessHoursScheduleView activeSchedule() {
        return schedules.findActiveSchedule()
                .orElseThrow(() -> new IllegalStateException("Active business-hours schedule is missing."));
    }

    private ResponseDeadline responseDeadline(BusinessHoursScheduleView schedule, Instant start, long minutes) {
        var target = Duration.ofMinutes(minutes);
        return new ResponseDeadline(businessTime.add(schedule, start, target.multipliedBy(3).dividedBy(4)),
                businessTime.add(schedule, start, target));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Instant lockForDelivery(UUID caseId) {
        jdbc.query("SELECT id FROM cases WHERE id = ? FOR UPDATE", rs -> { }, caseId);
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    @Override
    @Transactional
    public void customerMessage(UUID caseId, UUID messageId, Instant receivedAt) {
        // The intake command holds the Case row through this write. A response that
        // finishes concurrently observes either the entire inbound event or none of it.
        jdbc.update("UPDATE case_sla SET last_response_sender = 'CUSTOMER' WHERE case_id = ?", caseId);
        ResponseSnapshot snapshot = jdbc.query("""
                SELECT s.response_schedule::text, s.next_response_minutes
                FROM case_sla s JOIN cases c ON c.id = s.case_id
                WHERE c.id = ? AND c.workflow_status IN ('NEW', 'OPEN')
                  AND s.first_response_completed_at IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM case_next_response_cycles n WHERE n.case_id = c.id AND n.ended_at IS NULL)
                """, (rs, row) -> new ResponseSnapshot(rs.getString(1), rs.getInt(2)), caseId)
                .stream().findFirst().orElse(null);
        if (snapshot == null) return;
        if (snapshot.schedule() == null) {
            // Existing Cases have a known FRT deadline but no historical calendar.
            // Snapshot the policy on the first provable post-upgrade NRT.
            var policy = policies.active();
            var schedule = activeSchedule();
            snapshot = new ResponseSnapshot(json.writeValueAsString(schedule), policy.nextResponseMinutes());
            jdbc.update("""
                    UPDATE case_sla SET response_schedule = ?::jsonb, next_response_minutes = ?,
                        response_policy_version = ?, response_pause_waiting = ? WHERE case_id = ?
                    """, snapshot.schedule(), snapshot.minutes(), policy.version(), policy.pauseWaiting(), caseId);
        }
        ResponseDeadline deadline = responseDeadline(json.readValue(snapshot.schedule(), BusinessHoursScheduleView.class),
                receivedAt, snapshot.minutes());
        jdbc.update("""
                INSERT INTO case_next_response_cycles (case_id, opening_message_id, started_at, warning_at, due_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, caseId, messageId, at(receivedAt), at(deadline.warning()), at(deadline.due()));
    }

    @Override
    @Transactional
    public void humanResponse(UUID caseId, UUID messageId, Instant acceptedAt) {
        jdbc.update("UPDATE case_sla SET last_response_sender = 'SUPPORT' WHERE case_id = ?", caseId);
        recordProviderAcceptedResponse(caseId, acceptedAt);
        OffsetDateTime accepted = at(acceptedAt);
        jdbc.update("""
                UPDATE case_next_response_cycles
                SET ended_at = ?, outcome = 'RESPONDED', response_message_id = ?
                WHERE case_id = ? AND ended_at IS NULL
                """, accepted, messageId, caseId);
    }

    @Override
    @Transactional
    public void systemResponse(UUID caseId) {
        jdbc.update("UPDATE case_sla SET last_response_sender = 'SYSTEM' WHERE case_id = ?", caseId);
    }

    @Override
    @Transactional
    public void close(UUID caseId) {
        var terminal = jdbc.query("""
                SELECT c.status, c.created_at, COALESCE(c.resolved_at,c.ignored_at) AS ended_at,
                       s.response_schedule::text, s.first_response_warning_at, s.paused_at,
                       s.total_paused_seconds, s.resolved_pause_seconds
                FROM cases c LEFT JOIN case_sla s ON s.case_id = c.id
                WHERE c.id = ? AND c.workflow_status = 'RESOLVED'
                """, (rs, row) -> new Terminal(rs.getString("status"), instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("ended_at", OffsetDateTime.class)), rs.getString("response_schedule"),
                rs.getObject("first_response_warning_at", OffsetDateTime.class) != null,
                instant(rs.getObject("paused_at", OffsetDateTime.class)), rs.getLong("total_paused_seconds"),
                rs.getLong("resolved_pause_seconds")), caseId).stream().findFirst().orElse(null);
        if (terminal == null) return;
        OffsetDateTime ended = at(terminal.ended());
        jdbc.update("""
                UPDATE case_sla SET first_response_stopped_at = ?, first_response_breached_at =
                    COALESCE(first_response_breached_at, CASE WHEN first_response_due_at <= ? THEN first_response_due_at END)
                WHERE case_id = ? AND first_response_completed_at IS NULL AND first_response_stopped_at IS NULL
                """, ended, ended, caseId);
        jdbc.update("""
                UPDATE case_next_response_cycles SET ended_at = ?, outcome = ?
                WHERE case_id = ? AND ended_at IS NULL
                """, ended, terminal.status(), caseId);
        if (!"RESOLVED".equals(terminal.status())) return;
        Long work = null;
        if (terminal.snapshotAtCreation()) {
            var schedule = json.readValue(terminal.schedule(), BusinessHoursScheduleView.class);
            long openPause = terminal.pausedAt() == null ? 0
                    : businessTime.measure(schedule, terminal.pausedAt(), terminal.ended()).toSeconds();
            work = Math.max(0, businessTime.measure(schedule, terminal.created(), terminal.ended()).toSeconds()
                    - terminal.pausedSeconds() - terminal.closedSeconds() - openPause);
        }
        jdbc.update("""
                INSERT INTO case_resolution_times (case_id, ended_at, wall_seconds, working_seconds)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, caseId, ended, Duration.between(terminal.created(), terminal.ended()).toSeconds(), work);
    }

    private Instant dueAt(BusinessHoursScheduleView schedule, Instant startedAt, long minutes) {
        return businessTime.add(schedule, startedAt, Duration.ofMinutes(minutes));
    }

    @Override
    @Transactional
    public void recordClaim(UUID caseId, Instant claimedAt) {
        OffsetDateTime completedAt = OffsetDateTime.ofInstant(claimedAt, ZoneOffset.UTC);
        SlaPolicyView policy = policies.active();
        BusinessHoursScheduleView schedule = schedules.findActiveSchedule()
                .orElseThrow(() -> new IllegalStateException("Active business-hours schedule is missing."));
        OffsetDateTime warningAt = OffsetDateTime.ofInstant(dueAt(schedule, claimedAt, policy.inProgressWarningMinutes()), ZoneOffset.UTC);
        OffsetDateTime breachAt = OffsetDateTime.ofInstant(dueAt(schedule, claimedAt, policy.inProgressBreachMinutes()), ZoneOffset.UTC);
        jdbc.update("""
                UPDATE case_sla s
                SET unclaimed_completed_at = ?,
                    unclaimed_outcome = CASE
                        WHEN ? < unclaimed_warning_at THEN 'ACHIEVED'
                        WHEN ? < unclaimed_breach_at THEN 'WARNING'
                        ELSE 'BREACHED'
                    END,
                    state = CASE
                        WHEN ? >= unclaimed_breach_at THEN 'BREACHED'
                        WHEN ? >= unclaimed_warning_at THEN 'WARNING'
                        ELSE state
                    END,
                    in_progress_started_at = COALESCE(s.in_progress_started_at, ?),
                    in_progress_warning_at = COALESCE(s.in_progress_warning_at, ?),
                    in_progress_breach_at = COALESCE(s.in_progress_breach_at, ?),
                    updated_at = ?
                WHERE s.case_id = ? AND s.unclaimed_completed_at IS NULL
                """, completedAt, completedAt, completedAt, completedAt, completedAt,
                completedAt, warningAt, breachAt, completedAt, caseId);
    }

    @Override
    @Transactional
    public void recordProviderAcceptedResponse(UUID caseId, Instant sentAt) {
        OffsetDateTime completed = OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC);
        List<LateResponse> updated = jdbc.query("""
                UPDATE case_sla s SET first_response_completed_at = ?,
                    first_response_breached_at = COALESCE(s.first_response_breached_at,
                        CASE WHEN ? > s.first_response_due_at THEN s.first_response_due_at END),
                    state = CASE
                        WHEN ? > s.first_response_due_at THEN 'BREACHED'
                        WHEN s.state = 'PAUSED' THEN s.state
                        WHEN s.state = 'BREACHED' THEN s.state
                        ELSE 'ON_TRACK'
                    END,
                    updated_at = ?
                FROM cases c
                WHERE s.case_id = ? AND c.id = s.case_id AND c.workflow_status <> 'RESOLVED'
                  AND s.first_response_completed_at IS NULL AND s.first_response_stopped_at IS NULL
                RETURNING s.case_id, c.reference, (? > s.first_response_due_at) AS late
                """, (rs, row) -> new LateResponse(
                        rs.getObject("case_id", UUID.class), rs.getString("reference"), rs.getBoolean("late")),
                completed, completed, completed, completed, caseId, completed);
        updated.stream().filter(LateResponse::late).findFirst().ifPresent(this::enqueueFirstResponseBreach);
    }

    private void enqueueFirstResponseBreach(LateResponse response) {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) correlationId = UUID.randomUUID().toString();
        notifications.enqueue(new NotificationIntent(
                "sla_breached:" + response.caseId(), "sla_breached", "critical",
                "{\"caseId\":\"" + response.caseId() + "\",\"reference\":\"" + response.reference() + "\"}",
                correlationId));
    }

    @Override
    @Transactional
    public void pauseForWaiting(UUID caseId, Instant pausedAt) {
        OffsetDateTime paused = OffsetDateTime.ofInstant(pausedAt, ZoneOffset.UTC);
        jdbc.update("""
                UPDATE case_sla s
                SET paused_at = COALESCE(s.paused_at, ?),
                    state = 'PAUSED',
                    updated_at = ?
                FROM sla_policies p
                WHERE s.case_id = ?
                  AND s.policy_id = p.id
                  AND COALESCE(s.response_pause_waiting, p.pause_waiting) = TRUE
                """, paused, paused, caseId);
    }

    @Override
    @Transactional
    public void resumeAfterWaiting(UUID caseId, Instant resumedAt) {
        PausedSla current = jdbc.query("""
                SELECT s.paused_at, s.first_response_due_at, s.first_response_completed_at,
                       s.unclaimed_warning_at, s.unclaimed_breach_at,
                       s.in_progress_warning_at, s.in_progress_breach_at, s.total_paused_seconds,
                       s.first_response_warning_at, s.response_schedule::text
                FROM case_sla s JOIN sla_policies p ON p.id = s.policy_id
                WHERE s.case_id = ? AND COALESCE(s.response_pause_waiting, p.pause_waiting) = TRUE AND s.paused_at IS NOT NULL
                FOR UPDATE
                """, (rs, row) -> new PausedSla(
                rs.getObject("paused_at", OffsetDateTime.class).toInstant(),
                instant(rs.getObject("first_response_due_at", OffsetDateTime.class)),
                instant(rs.getObject("first_response_completed_at", OffsetDateTime.class)),
                instant(rs.getObject("unclaimed_warning_at", OffsetDateTime.class)),
                instant(rs.getObject("unclaimed_breach_at", OffsetDateTime.class)),
                instant(rs.getObject("in_progress_warning_at", OffsetDateTime.class)),
                instant(rs.getObject("in_progress_breach_at", OffsetDateTime.class)),
                rs.getLong("total_paused_seconds"),
                instant(rs.getObject("first_response_warning_at", OffsetDateTime.class)), rs.getString("response_schedule")), caseId).stream().findFirst().orElse(null);
        if (current == null) return;
        BusinessHoursScheduleView schedule = current.schedule() == null ? activeSchedule()
                : json.readValue(current.schedule(), BusinessHoursScheduleView.class);
        Duration pausedBusinessTime = businessTime.measure(schedule, current.pausedAt(), resumedAt);
        Instant firstWarning = current.firstResponseCompletedAt() == null
                ? shift(schedule, current.firstWarning(), pausedBusinessTime) : current.firstWarning();
        Instant firstDue = current.firstResponseCompletedAt() == null
                ? businessTime.add(schedule, current.firstResponseDueAt(), pausedBusinessTime)
                : current.firstResponseDueAt();
        Instant unclaimedWarning = businessTime.add(schedule, current.unclaimedWarningAt(), pausedBusinessTime);
        Instant unclaimedBreach = businessTime.add(schedule, current.unclaimedBreachAt(), pausedBusinessTime);
        Instant progressWarning = shift(schedule, current.inProgressWarningAt(), pausedBusinessTime);
        Instant progressBreach = shift(schedule, current.inProgressBreachAt(), pausedBusinessTime);
        String state = current.firstResponseCompletedAt() == null && resumedAt.isAfter(firstDue)
                ? "BREACHED" : "ON_TRACK";
        jdbc.update("""
                UPDATE case_sla SET total_paused_seconds = ?, first_response_due_at = ?, first_response_warning_at = ?,
                    unclaimed_warning_at = ?, unclaimed_breach_at = ?, in_progress_warning_at = ?,
                    in_progress_breach_at = ?, paused_at = NULL, state = ?, updated_at = ?
                WHERE case_id = ?
                """, current.totalPausedSeconds() + pausedBusinessTime.toSeconds(),
                at(firstDue), at(firstWarning), at(unclaimedWarning), at(unclaimedBreach), at(progressWarning), at(progressBreach),
                state, at(resumedAt), caseId);
    }

    @Override
    @Transactional
    public Map<String, Object> restartUnclaimed(UUID caseId, Instant restartedAt) {
        var policy = policies.active();
        var schedule = schedules.findActiveSchedule()
                .orElseThrow(() -> new IllegalStateException("Active business-hours schedule is missing."));
        var previous = jdbc.query("""
                SELECT state, unclaimed_outcome, unclaimed_started_at, unclaimed_completed_at,
                       unclaimed_warning_at, unclaimed_breach_at, in_progress_started_at,
                       in_progress_warning_at, in_progress_breach_at, paused_at
                FROM case_sla WHERE case_id = ? FOR UPDATE
                """, (rs, row) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("previousSlaState", rs.getString("state"));
            var outcome = rs.getString("unclaimed_outcome");
            if (outcome != null) values.put("previousUnclaimedOutcome", outcome);
            for (String key : List.of("unclaimed_started_at", "unclaimed_completed_at", "unclaimed_warning_at",
                    "unclaimed_breach_at", "in_progress_started_at", "in_progress_warning_at", "in_progress_breach_at", "paused_at")) {
                var timestamp = rs.getObject(key, OffsetDateTime.class);
                if (timestamp != null) values.put(key, timestamp.toInstant().toString());
            }
            return Map.copyOf(values);
        }, caseId).stream().findFirst().orElseGet(() -> {
            initialize(caseId, restartedAt);
            return Map.of();
        });
        // A reopened Case resumes its original unfinished FRT. The closed interval and
        // any waiting interval still open at resolution are excluded from working TTR.
        var closure = jdbc.query("""
                SELECT r.ended_at, s.paused_at, s.response_schedule::text
                FROM case_sla s JOIN case_resolution_times r ON r.case_id = s.case_id
                WHERE s.case_id = ? ORDER BY r.ended_at DESC LIMIT 1
                """, (rs, row) -> new Closure(instant(rs.getObject(1, OffsetDateTime.class)),
                instant(rs.getObject(2, OffsetDateTime.class)), rs.getString(3)), caseId).stream().findFirst().orElse(null);
        long closedBusiness = 0;
        if (closure != null && closure.schedule() != null) {
            Instant from = closure.pausedAt() == null ? closure.ended() : closure.pausedAt();
            closedBusiness = businessTime.measure(json.readValue(closure.schedule(), BusinessHoursScheduleView.class),
                    from, restartedAt).toSeconds();
        }
        jdbc.update("""
                UPDATE case_sla
                SET first_response_stopped_at = NULL, resolved_pause_seconds = resolved_pause_seconds + ?,
                    unclaimed_started_at = ?, unclaimed_warning_at = ?, unclaimed_breach_at = ?,
                    unclaimed_completed_at = NULL, unclaimed_outcome = NULL,
                    in_progress_started_at = NULL, in_progress_warning_at = NULL, in_progress_breach_at = NULL,
                    paused_at = NULL, updated_at = ?,
                    state = CASE WHEN COALESCE(first_response_completed_at, ?) > first_response_due_at
                                 THEN 'BREACHED' ELSE 'ON_TRACK' END
                WHERE case_id = ?
                """, closedBusiness, at(restartedAt), at(dueAt(schedule, restartedAt, policy.unclaimedWarningMinutes())),
                at(dueAt(schedule, restartedAt, policy.unclaimedBreachMinutes())),
                at(restartedAt), at(restartedAt), caseId);
        return previous;
    }

    private Instant shift(BusinessHoursScheduleView schedule, Instant value, Duration duration) {
        return value == null ? null : businessTime.add(schedule, value, duration);
    }

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static OffsetDateTime at(Instant value) { return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }

    private record PausedSla(Instant pausedAt, Instant firstResponseDueAt, Instant firstResponseCompletedAt,
                             Instant unclaimedWarningAt, Instant unclaimedBreachAt,
                             Instant inProgressWarningAt, Instant inProgressBreachAt,
                             long totalPausedSeconds, Instant firstWarning, String schedule) { }

    private record ResponseSnapshot(String schedule, int minutes) { }
    private record ResponseDeadline(Instant warning, Instant due) { }
    private record Terminal(String status, Instant created, Instant ended, String schedule, boolean snapshotAtCreation,
                            Instant pausedAt, long pausedSeconds, long closedSeconds) { }
    private record Closure(Instant ended, Instant pausedAt, String schedule) { }

    private record LateResponse(UUID caseId, String reference, boolean late) { }
}
