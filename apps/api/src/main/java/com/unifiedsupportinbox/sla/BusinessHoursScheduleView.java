package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BusinessHoursScheduleView(
        UUID id,
        String timezone,
        boolean active,
        List<Interval> intervals,
        List<ScheduleException> exceptions,
        String updatedBy,
        Instant updatedAt) {

    public record Interval(int dayOfWeek, String start, String end) {
    }

    public record ScheduleException(
            LocalDate date,
            String type,
            String start,
            String end,
            String note) {
    }
}
