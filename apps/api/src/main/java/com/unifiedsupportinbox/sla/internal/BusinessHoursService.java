package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BusinessHoursService {

    private static final String MANAGE_SCHEDULE = "manage_schedule";
    private static final Pattern HH_MM = Pattern.compile("^(?:[01]\\d|2[0-3]):[0-5]\\d$");
    private static final int MAX_INTERVALS = 64;

    private final BusinessHoursRepository schedules;

    BusinessHoursService(BusinessHoursRepository schedules) {
        this.schedules = schedules;
    }

    @Transactional(readOnly = true)
    BusinessHoursScheduleView getActive(Authentication actor) {
        requireManageSchedule(actor);
        return schedules.findActive()
                .orElseThrow(() -> new IllegalStateException("Active business-hours schedule is missing."));
    }

    @Transactional
    BusinessHoursScheduleView replaceActive(
            Authentication actor,
            String timezone,
            List<IntervalInput> intervalInputs) {
        requireManageSchedule(actor);
        String normalizedTimezone = normalizeTimezone(timezone);
        List<BusinessHoursIntervalValue> intervals = normalizeIntervals(intervalInputs);
        return schedules.replaceActive(normalizedTimezone, intervals, actor.getName());
    }

    private static String normalizeTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw ApiProblemException.validationFailed("Organization timezone is required.");
        }
        String normalized = timezone.strip();
        if (normalized.length() > 128) {
            throw ApiProblemException.validationFailed("Organization timezone is too long.");
        }
        try {
            ZoneId.of(normalized);
        } catch (DateTimeException exception) {
            throw ApiProblemException.validationFailed("Organization timezone must be a valid IANA timezone id.");
        }
        if (!ZoneId.getAvailableZoneIds().contains(normalized)) {
            throw ApiProblemException.validationFailed("Organization timezone must be a valid IANA timezone id.");
        }
        return normalized;
    }

    private static List<BusinessHoursIntervalValue> normalizeIntervals(List<IntervalInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw ApiProblemException.validationFailed(
                    "Active weekly business-hours schedule must contain at least one opening interval.");
        }
        if (inputs.size() > MAX_INTERVALS) {
            throw ApiProblemException.validationFailed("Weekly business-hours schedule contains too many intervals.");
        }

        List<BusinessHoursIntervalValue> intervals = new ArrayList<>(inputs.size());
        for (IntervalInput input : inputs) {
            if (input == null || input.dayOfWeek() == null || input.dayOfWeek() < 1 || input.dayOfWeek() > 7) {
                throw ApiProblemException.validationFailed("Business-hours dayOfWeek must be between 1 and 7.");
            }
            LocalTime start = parseWallClock(input.start(), "start");
            LocalTime end = parseWallClock(input.end(), "end");
            if (!start.isBefore(end)) {
                throw ApiProblemException.validationFailed(
                        "Business-hours intervals must end after they start; overnight hours must be split across days.");
            }
            intervals.add(new BusinessHoursIntervalValue(input.dayOfWeek(), start, end));
        }

        intervals.sort(Comparator.comparingInt(BusinessHoursIntervalValue::dayOfWeek)
                .thenComparing(BusinessHoursIntervalValue::start)
                .thenComparing(BusinessHoursIntervalValue::end));

        BusinessHoursIntervalValue previous = null;
        for (BusinessHoursIntervalValue current : intervals) {
            if (previous != null
                    && previous.dayOfWeek() == current.dayOfWeek()
                    && current.start().isBefore(previous.end())) {
                throw ApiProblemException.validationFailed(
                        "Business-hours intervals for the same day must not overlap.");
            }
            previous = current;
        }
        return List.copyOf(intervals);
    }

    private static LocalTime parseWallClock(String value, String field) {
        if (value == null || !HH_MM.matcher(value).matches()) {
            throw ApiProblemException.validationFailed(
                    "Business-hours " + field + " must use HH:mm local wall-clock format.");
        }
        return LocalTime.parse(value);
    }

    private static void requireManageSchedule(Authentication actor) {
        boolean allowed = actor != null
                && actor.isAuthenticated()
                && actor.getAuthorities().stream()
                        .anyMatch(authority -> MANAGE_SCHEDULE.equals(authority.getAuthority()));
        if (!allowed) throw ApiProblemException.accessDenied();
    }

    record IntervalInput(Integer dayOfWeek, String start, String end) {
    }
}

record BusinessHoursIntervalValue(int dayOfWeek, LocalTime start, LocalTime end) {
}
