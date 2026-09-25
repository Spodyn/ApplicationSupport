package com.unifiedsupportinbox.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class StatisticsOverviewRepository {

    private final JdbcTemplate jdbc;

    StatisticsOverviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String activeTimezone() {
        List<String> zones = jdbc.query("SELECT timezone_id FROM business_hours WHERE active = TRUE",
                (resultSet, row) -> resultSet.getString(1));
        return zones.isEmpty() ? "UTC" : zones.getFirst();
    }

    List<StatisticsOverviewService.Day> find(
            LocalDate from, LocalDate to, String timezone, String provider, UUID customerId) {
        StringBuilder sql = new StringBuilder("""
                SELECT reporting_date,
                       coalesce(sum(created_count), 0) AS created_count,
                       coalesce(sum(claimed_count), 0) AS claimed_count,
                       coalesce(sum(first_response_count), 0) AS first_response_count,
                       coalesce(sum(first_response_duration_seconds), 0) AS first_response_duration_seconds,
                       coalesce(sum(resolved_count), 0) AS resolved_count,
                       coalesce(sum(ignored_count), 0) AS ignored_count,
                       coalesce(sum(resolution_duration_seconds), 0) AS resolution_duration_seconds
                FROM analytics_daily_case_metrics
                WHERE reporting_date >= ? AND reporting_date <= ? AND timezone_id = ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(from, to, timezone));
        if (provider != null) {
            sql.append(" AND provider = ?");
            parameters.add(provider);
        }
        if (customerId != null) {
            sql.append(" AND customer_id = ?");
            parameters.add(customerId);
        }
        sql.append(" GROUP BY reporting_date ORDER BY reporting_date");
        return jdbc.query(sql.toString(), (resultSet, row) -> new StatisticsOverviewService.Day(
                resultSet.getObject("reporting_date", LocalDate.class),
                resultSet.getLong("created_count"), resultSet.getLong("claimed_count"),
                resultSet.getLong("first_response_count"), resultSet.getLong("first_response_duration_seconds"),
                resultSet.getLong("resolved_count"), resultSet.getLong("ignored_count"),
                resultSet.getLong("resolution_duration_seconds")), parameters.toArray());
    }
}
