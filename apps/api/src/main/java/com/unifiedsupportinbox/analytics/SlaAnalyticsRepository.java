package com.unifiedsupportinbox.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SLA snapshots remain canonical. When filtering by an employee, attribute
 * outcomes to the historical first sent-response / first claim actor; a
 * Case's mutable current owner cannot establish historical ownership.
 */
@Repository
class SlaAnalyticsRepository {
    private final JdbcTemplate jdbc;

    SlaAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<SlaAnalyticsService.Sample> completed(
            LocalDate from, LocalDate to, String timezone, String provider, UUID customerId, UUID userId) {
        String firstResponseFilters = filters(provider, customerId);
        String unclaimedFilters = filters(provider, customerId);
        if (userId != null) {
            // Match the canonical first sent human SUPPORT Message, not a later
            // reassigned owner or a queued/failed outgoing attempt.
            firstResponseFilters += """
                      AND EXISTS (
                          SELECT 1 FROM messages m
                          WHERE m.case_id = c.id AND m.author_user_id = ?
                            AND m.kind = 'SUPPORT'
                            AND m.delivery_status IN ('SENT', 'DELIVERED')
                            AND NOT EXISTS (
                                SELECT 1 FROM messages earlier
                                WHERE earlier.case_id = m.case_id
                                  AND earlier.kind = 'SUPPORT'
                                  AND earlier.delivery_status IN ('SENT', 'DELIVERED')
                                  AND (earlier.created_at, earlier.id) < (m.created_at, m.id)
                            )
                      )
                    """;
            // Unclaimed completion occurs on first successful Claim. Use the
            // append-only audit actor rather than Case.owner_user_id.
            unclaimedFilters += """
                      AND EXISTS (
                          SELECT 1 FROM audit_events a
                          WHERE a.case_id = c.id AND a.actor_user_id = ?
                            AND a.action = 'CASE_CLAIM'
                            AND NOT EXISTS (
                                SELECT 1 FROM audit_events earlier
                                WHERE earlier.case_id = a.case_id
                                  AND earlier.action = 'CASE_CLAIM'
                                  AND (earlier.occurred_at, earlier.id) < (a.occurred_at, a.id)
                            )
                      )
                    """;
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(timezone);
        parameters.add(from);
        parameters.add(to);
        addFilters(parameters, provider, customerId);
        if (userId != null) parameters.add(userId);
        parameters.add(timezone);
        parameters.add(from);
        parameters.add(to);
        addFilters(parameters, provider, customerId);
        if (userId != null) parameters.add(userId);

        String sql = """
                SELECT sla_type, outcome, duration_seconds FROM (
                    SELECT 'FIRST_RESPONSE' AS sla_type,
                           CASE WHEN s.first_response_completed_at <= s.first_response_due_at
                                THEN 'ACHIEVED' ELSE 'BREACHED' END AS outcome,
                           GREATEST(0, EXTRACT(EPOCH FROM (s.first_response_completed_at - s.first_response_started_at))::bigint) AS duration_seconds
                    FROM case_sla s JOIN cases c ON c.id = s.case_id
                    WHERE s.first_response_completed_at IS NOT NULL
                      AND (s.first_response_completed_at AT TIME ZONE ?)::date BETWEEN ? AND ?
                """ + firstResponseFilters + """
                    UNION ALL
                    SELECT 'UNCLAIMED' AS sla_type, s.unclaimed_outcome AS outcome,
                           GREATEST(0, EXTRACT(EPOCH FROM (s.unclaimed_completed_at - s.unclaimed_started_at))::bigint) AS duration_seconds
                    FROM case_sla s JOIN cases c ON c.id = s.case_id
                    WHERE s.unclaimed_completed_at IS NOT NULL AND s.unclaimed_outcome IS NOT NULL
                      AND (s.unclaimed_completed_at AT TIME ZONE ?)::date BETWEEN ? AND ?
                """ + unclaimedFilters + ") samples ORDER BY sla_type, duration_seconds";
        return jdbc.query(sql, (rs, row) -> new SlaAnalyticsService.Sample(
                SlaAnalyticsService.Type.valueOf(rs.getString("sla_type")),
                SlaAnalyticsService.Outcome.valueOf(rs.getString("outcome")), rs.getLong("duration_seconds")),
                parameters.toArray());
    }

    private static String filters(String provider, UUID customerId) {
        StringBuilder sql = new StringBuilder();
        if (provider != null) sql.append(" AND c.provider = ?");
        if (customerId != null) sql.append(" AND c.customer_id = ?");
        return sql.toString();
    }

    private static void addFilters(List<Object> parameters, String provider, UUID customerId) {
        if (provider != null) parameters.add(provider);
        if (customerId != null) parameters.add(customerId);
    }
}
