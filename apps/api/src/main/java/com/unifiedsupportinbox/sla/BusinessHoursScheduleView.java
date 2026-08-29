package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BusinessHoursScheduleView(
        UUID id,
        String timezone,
        boolean active,
        List<Interval> intervals,
        String updatedBy,
        Instant updatedAt) {

    public record Interval(int dayOfWeek, String start, String end) {
    }
}
