package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import com.unifiedsupportinbox.sla.BusinessTimeCalculator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import tools.jackson.databind.ObjectMapper;

/** Shared Case detail, queue, and SLA attention representation. */
record CaseResponseSla(String state, String kind, Instant warningAt, Instant dueAt, boolean historicallyBreached,
                       String lastSender, Long remainingBusinessSeconds) {
    private static final BusinessTimeCalculator BUSINESS_TIME = new BusinessTimeCalculator();

    static CaseResponseSla read(ResultSet rs, ObjectMapper json) throws SQLException {
        Instant due = instant(rs, "response_due_at");
        return new CaseResponseSla(rs.getString("response_state"), rs.getString("response_kind"),
                instant(rs, "response_warning_at"), due, rs.getBoolean("historically_breached"),
                rs.getString("last_response_sender"), remainingTime(rs, json, due));
    }

    private static Long remainingTime(ResultSet rs, ObjectMapper json, Instant due) throws SQLException {
        if (due == null) return null;
        Instant now = instant(rs, "response_observed_at");
        String snapshot = rs.getString("response_schedule");
        if (snapshot == null) return Duration.between(now, due).toSeconds();
        var schedule = json.readValue(snapshot, BusinessHoursScheduleView.class);
        return now.isAfter(due) ? -BUSINESS_TIME.measure(schedule, due, now).toSeconds()
                : BUSINESS_TIME.measure(schedule, now, due).toSeconds();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
