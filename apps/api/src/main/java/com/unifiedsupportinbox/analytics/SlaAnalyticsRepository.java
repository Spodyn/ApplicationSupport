package com.unifiedsupportinbox.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the SLA policy snapshots attached to cases; it never consults the currently active policy. */
@Repository
class SlaAnalyticsRepository {
    private final JdbcTemplate jdbc;

    SlaAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<SlaAnalyticsService.Sample> completed(
            LocalDate from, LocalDate to, String timezone, String provider, UUID customerId, UUID userId) {
        String filters = filters(provider, customerId, userId);
        List<Object> parameters = new ArrayList<>();
        parameters.add(timezone); parameters.add(from); parameters.add(to);
        addFilters(parameters, provider, customerId, userId);
        parameters.add(timezone); parameters.add(from); parameters.add(to);
        addFilters(parameters, provider, customerId, userId);
        String sql = """
                SELECT sla_type, outcome, duration_seconds FROM (
                    SELECT 'FIRST_RESPONSE' AS sla_type,
                           CASE WHEN s.first_response_completed_at <= s.first_response_due_at
                                THEN 'ACHIEVED' ELSE 'BREACHED' END AS outcome,
                           GREATEST(0, EXTRACT(EPOCH FROM (s.first_response_completed_at - s.first_response_started_at))::bigint) AS duration_seconds
                    FROM case_sla s JOIN cases c ON c.id = s.case_id
                    WHERE s.first_response_completed_at IS NOT NULL
                      AND (s.first_response_completed_at AT TIME ZONE ?)::date BETWEEN ? AND ?
                """ + filters + """
                    UNION ALL
                    SELECT 'UNCLAIMED' AS sla_type, s.unclaimed_outcome AS outcome,
                           GREATEST(0, EXTRACT(EPOCH FROM (s.unclaimed_completed_at - s.unclaimed_started_at))::bigint) AS duration_seconds
                    FROM case_sla s JOIN cases c ON c.id = s.case_id
                    WHERE s.unclaimed_completed_at IS NOT NULL AND s.unclaimed_outcome IS NOT NULL
                      AND (s.unclaimed_completed_at AT TIME ZONE ?)::date BETWEEN ? AND ?
                """ + filters + ") samples ORDER BY sla_type, duration_seconds";
        return jdbc.query(sql, (rs, row) -> new SlaAnalyticsService.Sample(
                SlaAnalyticsService.Type.valueOf(rs.getString("sla_type")),
                SlaAnalyticsService.Outcome.valueOf(rs.getString("outcome")), rs.getLong("duration_seconds")),
                parameters.toArray());
    }

    private static String filters(String provider, UUID customerId, UUID userId) {
        StringBuilder sql = new StringBuilder();
        if (provider != null) sql.append(" AND c.provider = ?");
        if (customerId != null) sql.append(" AND c.customer_id = ?");
        if (userId != null) sql.append(" AND c.owner_user_id = ?");
        return sql.toString();
    }

    private static void addFilters(List<Object> parameters, String provider, UUID customerId, UUID userId) {
        if (provider != null) parameters.add(provider);
        if (customerId != null) parameters.add(customerId);
        if (userId != null) parameters.add(userId);
    }
}
