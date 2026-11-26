package com.unifiedsupportinbox.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

    @Test void liveClockIntervalsHonorSplitOvernightHoursAndTheHolidayOnTheSecondDay() {
        var overnight = new BusinessHoursScheduleView(UUID.randomUUID(), "Europe/Warsaw", true,
                List.of(new BusinessHoursScheduleView.Interval(1, "22:00", "23:59"),
                        new BusinessHoursScheduleView.Interval(2, "00:00", "05:00"),
                        new BusinessHoursScheduleView.Interval(3, "00:00", "05:00")),
                List.of(new BusinessHoursScheduleView.ScheduleException(LocalDate.parse("2026-03-10"),
                        "CLOSED", null, null, "Holiday")), "test", Instant.EPOCH);
        Instant start = Instant.parse("2026-03-09T22:58:00Z");
        Instant end = Instant.parse("2026-03-11T00:02:00Z");
        assertThat(calculator.intervals(overnight, start, end)).containsExactly(
                new BusinessTimeCalculator.Window(start, Instant.parse("2026-03-09T22:59:00Z")),
                new BusinessTimeCalculator.Window(Instant.parse("2026-03-10T23:00:00Z"), end));
        assertThat(calculator.measure(overnight, start, end)).isEqualTo(Duration.ofMinutes(63));
    }
}
