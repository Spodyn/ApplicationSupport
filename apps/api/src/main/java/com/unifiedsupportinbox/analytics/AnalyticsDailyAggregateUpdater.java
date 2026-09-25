package com.unifiedsupportinbox.analytics;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds disposable dashboard aggregates from durable Cases, Messages and audit events. */
@Component
public class AnalyticsDailyAggregateUpdater {

    private static final int LATE_EVENT_REBUILD_DAYS = 3;

    private final JdbcTemplate jdbc;

    public AnalyticsDailyAggregateUpdater(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${usi.analytics.aggregate-refresh-interval:1h}")
    void scheduledRefresh() {
        ZoneId zone = activeZone();
        LocalDate today = LocalDate.now(zone);
        for (int offset = 0; offset <= LATE_EVENT_REBUILD_DAYS; offset++) {
            rebuild(today.minusDays(offset), zone);
        }
    }

    @Transactional
    public void rebuild(LocalDate reportingDate, ZoneId zone) {
        Instant from = reportingDate.atStartOfDay(zone).toInstant();
        Instant until = reportingDate.plusDays(1).atStartOfDay(zone).toInstant();
        String timezone = zone.getId();
        jdbc.update("DELETE FROM analytics_daily_case_metrics WHERE reporting_date = ? AND timezone_id = ?",
                reportingDate, timezone);
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO analytics_daily_case_metrics (
                        reporting_date, timezone_id, customer_id, provider, user_id,
                        created_count, claimed_count, first_response_count, first_response_duration_seconds,
                        resolved_count, ignored_count, resolution_duration_seconds, rebuilt_at
                    )
                    SELECT ?, ?, customer_id, provider, user_id,
                           sum(created_count), sum(claimed_count), sum(first_response_count), sum(first_response_duration_seconds),
                           sum(resolved_count), sum(ignored_count), sum(resolution_duration_seconds), statement_timestamp()
                    FROM (
                        SELECT c.customer_id, c.provider, NULL::uuid AS user_id,
                               1::bigint AS created_count, 0::bigint AS claimed_count,
                               0::bigint AS first_response_count, 0::bigint AS first_response_duration_seconds,
                               0::bigint AS resolved_count, 0::bigint AS ignored_count, 0::bigint AS resolution_duration_seconds
                        FROM cases c WHERE c.created_at >= ? AND c.created_at < ?
                        UNION ALL
                        SELECT c.customer_id, c.provider, a.actor_user_id,
                               0, 1, 0, 0, 0, 0, 0
                        FROM audit_events a JOIN cases c ON c.id = a.case_id
                        WHERE a.action = 'CASE_CLAIM' AND a.occurred_at >= ? AND a.occurred_at < ?
                        UNION ALL
                        SELECT c.customer_id, c.provider, m.author_user_id,
                               0, 0, 1,
                               greatest(0, extract(epoch FROM m.created_at - c.created_at)::bigint), 0, 0, 0
                        FROM messages m JOIN cases c ON c.id = m.case_id
                        WHERE m.kind = 'SUPPORT' AND m.delivery_status IN ('SENT', 'DELIVERED')
                          AND m.created_at >= ? AND m.created_at < ?
                          AND NOT EXISTS (
                              SELECT 1 FROM messages earlier
                              WHERE earlier.case_id = m.case_id AND earlier.kind = 'SUPPORT'
                                AND earlier.delivery_status IN ('SENT', 'DELIVERED')
                                AND (earlier.created_at, earlier.id) < (m.created_at, m.id))
                        UNION ALL
                        SELECT c.customer_id, c.provider, a.actor_user_id,
                               0, 0, 0, 0, 1, 0,
                               greatest(0, extract(epoch FROM a.occurred_at - c.created_at)::bigint)
                        FROM audit_events a JOIN cases c ON c.id = a.case_id
                        WHERE a.action IN ('CASE_RESOLVE', 'CASE_FORCE_RESOLVE')
                          AND a.occurred_at >= ? AND a.occurred_at < ?
                        UNION ALL
                        SELECT c.customer_id, c.provider, NULL::uuid,
                               0, 0, 0, 0, 0, 1, 0
                        FROM audit_events a JOIN cases c ON c.id = a.case_id
                        WHERE a.action = 'CASE_IGNORE' AND a.occurred_at >= ? AND a.occurred_at < ?
                    ) events
                    GROUP BY customer_id, provider, user_id
                    """);
            statement.setObject(1, reportingDate);
            statement.setString(2, timezone);
            for (int index = 3; index <= 12; index += 2) {
                statement.setTimestamp(index, Timestamp.from(from));
                statement.setTimestamp(index + 1, Timestamp.from(until));
            }
            return statement;
        });
        jdbc.update("""
                INSERT INTO analytics_aggregate_rebuilds (reporting_date, timezone_id, source_high_watermark)
                VALUES (?, ?, statement_timestamp())
                ON CONFLICT (reporting_date, timezone_id) DO UPDATE
                SET completed_at = statement_timestamp(), source_high_watermark = EXCLUDED.source_high_watermark
                """, reportingDate, timezone);
    }

    private ZoneId activeZone() {
        List<String> zones = jdbc.query("SELECT timezone_id FROM business_hours WHERE active = TRUE",
                (resultSet, row) -> resultSet.getString(1));
        return ZoneId.of(zones.isEmpty() ? "UTC" : zones.getFirst());
    }
}
