package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.sla.CaseSlaInitializer;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaseSlaService implements CaseSlaInitializer, CaseSlaClaimRecorder, CaseSlaWaitingRecorder, CaseSlaResponseRecorder {
    private final JdbcTemplate jdbc;
    private final SlaPolicyRepository policies;
    private final BusinessHoursScheduleCatalog schedules;
    private final NotificationDeliveryQueue notifications;
    private final BusinessTimeCalculator businessTime = new BusinessTimeCalculator();

    CaseSlaService(
            JdbcTemplate jdbc,
            SlaPolicyRepository policies,
            BusinessHoursScheduleCatalog schedules,
            NotificationDeliveryQueue notifications) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.schedules = schedules;
        this.notifications = notifications;
    }

    @Override @Transactional
    public void initialize(UUID caseId, Instant createdAt) {
        SlaPolicyView policy = policies.active();
        BusinessHoursScheduleView schedule = schedules.findActiveSchedule()
                .orElseThrow(() -> new IllegalStateException("Active business-hours schedule is missing."));
        Instant firstResponseDue = dueAt(schedule, createdAt, policy.firstResponseMinutes());
        Instant unclaimedWarning = dueAt(schedule, createdAt, policy.unclaimedWarningMinutes());
        Instant unclaimedBreach = dueAt(schedule, createdAt, policy.unclaimedBreachMinutes());
        OffsetDateTime startedAt = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO case_sla (case_id, policy_id, first_response_started_at, first_response_due_at, unclaimed_started_at, unclaimed_warning_at, unclaimed_breach_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (case_id) DO NOTHING
                """, caseId, policy.id(), startedAt,
                OffsetDateTime.ofInstant(firstResponseDue, ZoneOffset.UTC), startedAt,
                OffsetDateTime.ofInstant(unclaimedWarning, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(unclaimedBreach, ZoneOffset.UTC));
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
                    state = CASE
                        WHEN ? > s.first_response_due_at THEN 'BREACHED'
                        WHEN s.state = 'PAUSED' THEN s.state
                        WHEN s.state = 'BREACHED' THEN s.state
                        ELSE 'ON_TRACK'
                    END,
                    updated_at = ?
                FROM cases c
                WHERE s.case_id = ? AND c.id = s.case_id AND s.first_response_completed_at IS NULL
                RETURNING s.case_id, c.reference, (? > s.first_response_due_at) AS late
                """, (rs, row) -> new LateResponse(
                        rs.getObject("case_id", UUID.class), rs.getString("reference"), rs.getBoolean("late")),
                completed, completed, completed, caseId, completed);
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
                  AND p.pause_waiting = TRUE
                """, paused, paused, caseId);
    }

    @Override
    @Transactional
    public void resumeAfterWaiting(UUID caseId, Instant resumedAt) {
        PausedSla current = jdbc.query("""
                SELECT s.paused_at, s.first_response_due_at, s.first_response_completed_at,
                       s.unclaimed_warning_at, s.unclaimed_breach_at,
                       s.in_progress_warning_at, s.in_progress_breach_at, s.total_paused_seconds
                FROM case_sla s JOIN sla_policies p ON p.id = s.policy_id
                WHERE s.case_id = ? AND p.pause_waiting = TRUE AND s.paused_at IS NOT NULL
                FOR UPDATE
                """, (rs, row) -> new PausedSla(
                rs.getObject("paused_at", OffsetDateTime.class).toInstant(),
                instant(rs.getObject("first_response_due_at", OffsetDateTime.class)),
                instant(rs.getObject("first_response_completed_at", OffsetDateTime.class)),
                instant(rs.getObject("unclaimed_warning_at", OffsetDateTime.class)),
                instant(rs.getObject("unclaimed_breach_at", OffsetDateTime.class)),
                instant(rs.getObject("in_progress_warning_at", OffsetDateTime.class)),
                instant(rs.getObject("in_progress_breach_at", OffsetDateTime.class)),
                rs.getLong("total_paused_seconds")), caseId).stream().findFirst().orElse(null);
        if (current == null) return;
        BusinessHoursScheduleView schedule = schedules.findActiveSchedule()
                .orElseThrow(() -> new IllegalStateException("Active business-hours schedule is missing."));
        Duration pausedBusinessTime = businessTime.measure(schedule, current.pausedAt(), resumedAt);
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
                UPDATE case_sla SET total_paused_seconds = ?, first_response_due_at = ?,
                    unclaimed_warning_at = ?, unclaimed_breach_at = ?, in_progress_warning_at = ?,
                    in_progress_breach_at = ?, paused_at = NULL, state = ?, updated_at = ?
                WHERE case_id = ?
                """, current.totalPausedSeconds() + pausedBusinessTime.toSeconds(),
                at(firstDue), at(unclaimedWarning), at(unclaimedBreach), at(progressWarning), at(progressBreach),
                state, at(resumedAt), caseId);
    }

    private Instant shift(BusinessHoursScheduleView schedule, Instant value, Duration duration) {
        return value == null ? null : businessTime.add(schedule, value, duration);
    }

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static OffsetDateTime at(Instant value) { return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }

    private record PausedSla(Instant pausedAt, Instant firstResponseDueAt, Instant firstResponseCompletedAt,
                             Instant unclaimedWarningAt, Instant unclaimedBreachAt,
                             Instant inProgressWarningAt, Instant inProgressBreachAt,
                             long totalPausedSeconds) { }

    private record LateResponse(UUID caseId, String reference, boolean late) { }
}
