package com.unifiedsupportinbox.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BusinessTimeCalculatorTests {
    private final BusinessTimeCalculator calculator = new BusinessTimeCalculator();
    private final BusinessHoursScheduleView schedule = new BusinessHoursScheduleView(UUID.randomUUID(), "Europe/Warsaw", true,
            List.of(new BusinessHoursScheduleView.Interval(1, "09:00", "17:00")), List.of(), "test", Instant.EPOCH);

    @Test void skipsWeekendAndMeasuresOnlyOpenTime() {
        Instant friday = Instant.parse("2026-03-06T15:00:00Z"); // 16:00 Warsaw
        assertThat(calculator.add(schedule, friday, Duration.ofHours(2))).isEqualTo(Instant.parse("2026-03-09T10:00:00Z"));
        assertThat(calculator.measure(schedule, friday, Instant.parse("2026-03-09T10:00:00Z"))).isEqualTo(Duration.ofHours(2));
    }
}
