package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class BusinessHoursRepository {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final RowMapper<ScheduleRow> SCHEDULE_ROW_MAPPER = BusinessHoursRepository::mapSchedule;

    private final JdbcTemplate jdbc;

    BusinessHoursRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<BusinessHoursScheduleView> findActive() {
        return jdbc.query("""
                SELECT id, timezone_id, active, updated_by, updated_at
                FROM business_hours
                WHERE active = TRUE
                """, SCHEDULE_ROW_MAPPER).stream()
                .findFirst()
                .map(this::toView);
    }

    BusinessHoursScheduleView replaceActive(
            String timezone,
            List<BusinessHoursIntervalValue> intervals,
            String updatedBy) {
        UUID scheduleId = jdbc.query("""
                SELECT id
                FROM business_hours
                WHERE active = TRUE
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Active business-hours schedule is missing."));

        jdbc.update("""
                UPDATE business_hours
                SET timezone_id = ?,
                    updated_by = ?,
                    updated_at = NOW()
                WHERE id = ?
                """, timezone, updatedBy, scheduleId);
        jdbc.update("DELETE FROM business_hour_intervals WHERE business_hours_id = ?", scheduleId);

        for (BusinessHoursIntervalValue interval : intervals) {
            jdbc.update("""
                    INSERT INTO business_hour_intervals (
                        business_hours_id,
                        day_of_week,
                        start_time,
                        end_time
                    ) VALUES (?, ?, ?, ?)
                    """, scheduleId, interval.dayOfWeek(), interval.start(), interval.end());
        }

        return findById(scheduleId)
                .orElseThrow(() -> new IllegalStateException("Updated business-hours schedule is missing."));
    }

    private Optional<BusinessHoursScheduleView> findById(UUID id) {
        return jdbc.query("""
                SELECT id, timezone_id, active, updated_by, updated_at
                FROM business_hours
                WHERE id = ?
                """, SCHEDULE_ROW_MAPPER, id).stream()
                .findFirst()
                .map(this::toView);
    }

    private BusinessHoursScheduleView toView(ScheduleRow row) {
        List<BusinessHoursScheduleView.Interval> intervals = jdbc.query("""
                SELECT day_of_week, start_time, end_time
                FROM business_hour_intervals
                WHERE business_hours_id = ?
                ORDER BY day_of_week, start_time, end_time, id
                """, (resultSet, rowNumber) -> new BusinessHoursScheduleView.Interval(
                        resultSet.getInt("day_of_week"),
                        formatTime(resultSet.getObject("start_time", LocalTime.class)),
                        formatTime(resultSet.getObject("end_time", LocalTime.class))), row.id());
        List<BusinessHoursScheduleView.ScheduleException> exceptions = jdbc.query("""
                SELECT exception_date, type, start_time, end_time, note
                FROM schedule_exceptions
                WHERE business_hours_id = ?
                ORDER BY exception_date, type, start_time NULLS FIRST, end_time NULLS FIRST, id
                """, (resultSet, rowNumber) -> new BusinessHoursScheduleView.ScheduleException(
                        resultSet.getObject("exception_date", LocalDate.class),
                        resultSet.getString("type"),
                        nullableTime(resultSet.getObject("start_time", LocalTime.class)),
                        nullableTime(resultSet.getObject("end_time", LocalTime.class)),
                        resultSet.getString("note")), row.id());
        return new BusinessHoursScheduleView(
                row.id(),
                row.timezone(),
                row.active(),
                List.copyOf(intervals),
                List.copyOf(exceptions),
                row.updatedBy(),
                row.updatedAt().toInstant());
    }

    private static ScheduleRow mapSchedule(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ScheduleRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("timezone_id"),
                resultSet.getBoolean("active"),
                resultSet.getString("updated_by"),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    private static String formatTime(LocalTime value) {
        return value.format(TIME_FORMAT);
    }

    void replaceExceptions(UUID scheduleId, List<ScheduleExceptionValue> exceptions) {
        jdbc.update("DELETE FROM schedule_exceptions WHERE business_hours_id = ?", scheduleId);
        for (ScheduleExceptionValue exception : exceptions) {
            jdbc.update("""
                    INSERT INTO schedule_exceptions (
                        business_hours_id, exception_date, type, start_time, end_time, note
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, scheduleId, exception.date(), exception.type(), exception.start(), exception.end(), exception.note());
        }
    }

    private static String nullableTime(LocalTime value) {
        return value == null ? null : formatTime(value);
    }

    private record ScheduleRow(
            UUID id,
            String timezone,
            boolean active,
            String updatedBy,
            OffsetDateTime updatedAt) {
    }
}
