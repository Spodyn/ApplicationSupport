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

    @Test void reportsCurrentOrNextOpeningWithoutInventingAnUnavailableTime() {
        assertThat(calculator.opening(schedule, Instant.parse("2026-03-09T10:00:00Z")))
                .isEqualTo(new BusinessTimeCalculator.Opening(true, Instant.parse("2026-03-09T10:00:00Z"), null));
        assertThat(calculator.opening(schedule, Instant.parse("2026-03-09T17:00:00Z")))
                .isEqualTo(new BusinessTimeCalculator.Opening(false, null, Instant.parse("2026-03-16T08:00:00Z")));
        BusinessHoursScheduleView empty = new BusinessHoursScheduleView(UUID.randomUUID(), "Europe/Warsaw", true,
                List.of(), List.of(), "test", Instant.EPOCH);
        assertThat(calculator.opening(empty, Instant.parse("2026-03-09T10:00:00Z")).nextOpening()).isNull();
    }
}
