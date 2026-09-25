package com.unifiedsupportinbox.sla;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic server-side business-time arithmetic over the persisted schedule view. */
public final class BusinessTimeCalculator {

    private static final int MAX_SEARCH_DAYS = 3_660;

    public Instant add(BusinessHoursScheduleView schedule, Instant start, Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) throw new IllegalArgumentException("duration must not be negative");
        if (duration.isZero()) return start;
        ZoneId zone = ZoneId.of(schedule.timezone());
        Instant cursor = start;
        Duration remaining = duration;
        for (int days = 0; days < MAX_SEARCH_DAYS; days++) {
            LocalDate date = cursor.atZone(zone).toLocalDate();
            for (Window window : windows(schedule, date, zone)) {
                Instant from = cursor.isAfter(window.start()) ? cursor : window.start();
                if (!from.isBefore(window.end())) continue;
                Duration available = Duration.between(from, window.end());
                if (available.compareTo(remaining) >= 0) return from.plus(remaining);
                remaining = remaining.minus(available);
                cursor = window.end();
            }
            cursor = date.plusDays(1).atStartOfDay(zone).toInstant();
        }
        throw new IllegalArgumentException("No sufficient business time exists within the supported search range.");
    }

    public Duration measure(BusinessHoursScheduleView schedule, Instant from, Instant to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("to must not be before from");
        ZoneId zone = ZoneId.of(schedule.timezone());
        Duration result = Duration.ZERO;
        LocalDate date = from.atZone(zone).toLocalDate();
        LocalDate last = to.atZone(zone).toLocalDate();
        while (!date.isAfter(last)) {
            for (Window window : windows(schedule, date, zone)) {
                Instant start = from.isAfter(window.start()) ? from : window.start();
                Instant end = to.isBefore(window.end()) ? to : window.end();
                if (start.isBefore(end)) result = result.plus(Duration.between(start, end));
            }
            date = date.plusDays(1);
        }
        return result;
    }

    public Instant nextOpening(BusinessHoursScheduleView schedule, Instant at) {
        return opening(schedule, at).nextOpening();
    }

    /** Reports the current state without inventing an opening for broken/legacy schedules. */
    public Opening opening(BusinessHoursScheduleView schedule, Instant at) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(at, "at");
        ZoneId zone = ZoneId.of(schedule.timezone());
        Instant cursor = at;
        for (int days = 0; days < MAX_SEARCH_DAYS; days++) {
            LocalDate date = cursor.atZone(zone).toLocalDate();
            for (Window window : windows(schedule, date, zone)) {
                if (!cursor.isBefore(window.end())) continue;
                if (!cursor.isBefore(window.start())) return new Opening(true, cursor, null);
                return new Opening(false, null, window.start());
            }
            cursor = date.plusDays(1).atStartOfDay(zone).toInstant();
        }
        return new Opening(false, null, null);
    }

    private static List<Window> windows(BusinessHoursScheduleView schedule, LocalDate date, ZoneId zone) {
        List<BusinessHoursScheduleView.ScheduleException> exceptions = schedule.exceptions().stream()
                .filter(value -> date.equals(value.date())).toList();
        if (exceptions.stream().anyMatch(value -> "CLOSED".equals(value.type()))) return List.of();
        List<TimeRange> ranges = exceptions.isEmpty()
                ? schedule.intervals().stream().filter(value -> value.dayOfWeek() == date.getDayOfWeek().getValue())
                        .map(value -> new TimeRange(LocalTime.parse(value.start()), LocalTime.parse(value.end()))).toList()
                : exceptions.stream().filter(value -> "OPEN".equals(value.type()) || "OVERRIDE".equals(value.type()))
                        .map(value -> new TimeRange(LocalTime.parse(value.start()), LocalTime.parse(value.end()))).toList();
        List<Window> windows = new ArrayList<>();
        for (TimeRange range : ranges) {
            ZonedDateTime start = LocalDateTime.of(date, range.start()).atZone(zone);
            ZonedDateTime end = LocalDateTime.of(date, range.end()).atZone(zone);
            if (start.toInstant().isBefore(end.toInstant())) windows.add(new Window(start.toInstant(), end.toInstant()));
        }
        windows.sort(Comparator.comparing(Window::start));
        return windows;
    }

    private record TimeRange(LocalTime start, LocalTime end) {}
    private record Window(Instant start, Instant end) {}

    /** A null nextOpening is the explicit NO_FUTURE_OPENING configuration-fault result. */
    public record Opening(boolean open, Instant currentOpening, Instant nextOpening) {}
}
