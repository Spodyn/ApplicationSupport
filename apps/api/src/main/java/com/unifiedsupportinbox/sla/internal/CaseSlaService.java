package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.sla.CaseSlaInitializer;
import com.unifiedsupportinbox.sla.CaseSlaClaimRecorder;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleCatalog;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import com.unifiedsupportinbox.sla.BusinessTimeCalculator;
import com.unifiedsupportinbox.sla.SlaPolicyView;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaseSlaService implements CaseSlaInitializer, CaseSlaClaimRecorder {
    private final JdbcTemplate jdbc;
    private final SlaPolicyRepository policies;
    private final BusinessHoursScheduleCatalog schedules;
    private final BusinessTimeCalculator businessTime = new BusinessTimeCalculator();

    CaseSlaService(
            JdbcTemplate jdbc,
            SlaPolicyRepository policies,
            BusinessHoursScheduleCatalog schedules) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.schedules = schedules;
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
        jdbc.update("""
                UPDATE case_sla
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
                    updated_at = ?
                WHERE case_id = ? AND unclaimed_completed_at IS NULL
                """, completedAt, completedAt, completedAt, completedAt, completedAt, completedAt, caseId);
    }
}
