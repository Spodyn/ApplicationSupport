package com.unifiedsupportinbox.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reporting is attributed to the historical actor dimension, never to the
 * Case's current owner (which may have changed since the event occurred).
 */
@Repository
class UserStatisticsRepository {

    private final JdbcTemplate jdbc;

    UserStatisticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UserStatisticsService.Metrics> find(
            UUID userId, LocalDate from, LocalDate to, String timezone,
            String provider, UUID customerId) {
        StringBuilder sql = new StringBuilder("""
                SELECT u.id, u.display_name, u.active,
                       COALESCE(SUM(a.claimed_count), 0) AS claimed,
                       COALESCE(SUM(a.first_response_count), 0) AS first_responses,
                       COALESCE(SUM(a.first_response_duration_seconds), 0) AS first_response_seconds,
                       COALESCE(SUM(a.resolved_count), 0) AS resolved,
                       COALESCE(SUM(a.resolution_duration_seconds), 0) AS resolution_seconds
                FROM users u
                LEFT JOIN analytics_daily_case_metrics a
                       ON a.user_id = u.id
                      AND a.reporting_date BETWEEN ? AND ?
                      AND a.timezone_id = ?
                """);
        List<Object> values = new ArrayList<>(List.of(from, to, timezone));
        if (provider != null) {
            sql.append(" AND a.provider = ?");
            values.add(provider);
        }
        if (customerId != null) {
            sql.append(" AND a.customer_id = ?");
            values.add(customerId);
        }
        sql.append(" WHERE u.id = ? GROUP BY u.id, u.display_name, u.active");
        values.add(userId);

        return jdbc.query(sql.toString(), (rs, row) -> {
            long firstResponses = rs.getLong("first_responses");
            long resolved = rs.getLong("resolved");
            return new UserStatisticsService.Metrics(
                    rs.getObject("id", UUID.class),
                    rs.getString("display_name"),
                    rs.getBoolean("active"),
                    rs.getLong("claimed"),
                    firstResponses,
                    firstResponses == 0 ? null : rs.getLong("first_response_seconds") / firstResponses,
                    resolved,
                    resolved == 0 ? null : rs.getLong("resolution_seconds") / resolved);
        }, values.toArray()).stream().findFirst();
    }
}
