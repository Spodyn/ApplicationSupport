package com.unifiedsupportinbox.workflow;

import static com.unifiedsupportinbox.workflow.CaseResponseSla.instant;

import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import com.unifiedsupportinbox.sla.BusinessTimeCalculator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

record CaseOperationalDeadline(UUID id, String kind, String state, Instant startedAt, Instant warningAt,
        Instant dueAt, Instant observedAt, Instant clockValidUntil, long remainingMillis,
        List<BusinessTimeCalculator.Window> businessIntervals) {
    private static final BusinessTimeCalculator BUSINESS_TIME = new BusinessTimeCalculator();

    static CaseOperationalDeadline read(ResultSet rs, ObjectMapper mapper) throws SQLException {
        UUID id = rs.getObject("operational_id", UUID.class);
        if (id == null) return null;
        Instant due = instant(rs, "operational_due_at");
        Instant observed = instant(rs, "response_observed_at");
        Instant validUntil = observed.plus(Duration.ofHours(24));
        String snapshot = rs.getString("operational_schedule");
        List<BusinessTimeCalculator.Window> intervals = null;
        long remaining = Duration.between(observed, due).toMillis();
        if (snapshot != null) {
            var schedule = mapper.readValue(snapshot, BusinessHoursScheduleView.class);
            remaining = observed.isAfter(due) ? -BUSINESS_TIME.measure(schedule, due, observed).toMillis()
                    : BUSINESS_TIME.measure(schedule, observed, due).toMillis();
            intervals = BUSINESS_TIME.intervals(schedule, observed, validUntil);
        }
        return new CaseOperationalDeadline(id, rs.getString("operational_kind"), rs.getString("operational_state"),
                instant(rs, "operational_started_at"), instant(rs, "operational_warning_at"),
                due, observed, validUntil, remaining, intervals);
    }
}
