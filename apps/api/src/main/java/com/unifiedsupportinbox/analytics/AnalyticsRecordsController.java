package com.unifiedsupportinbox.analytics;

import com.unifiedsupportinbox.ApiProblemException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Persisted case facts used by the interactive dashboard; no message body or secret is exposed. */
@RestController
@RequestMapping("/api/v1/statistics")
class AnalyticsRecordsController {

    static final String RECORDS_SQL = """
                SELECT c.id, c.reference, c.created_at, c.claimed_at,
                       COALESCE(c.resolved_at, c.ignored_at) AS completed_at,
                       (c.created_at AT TIME ZONE business.timezone_id)::date::text AS created_date,
                       (c.claimed_at AT TIME ZONE business.timezone_id)::date::text AS claimed_date,
                       (COALESCE(c.resolved_at, c.ignored_at) AT TIME ZONE business.timezone_id)::date::text AS resolved_date,
                       EXTRACT(isodow FROM c.created_at AT TIME ZONE business.timezone_id)::integer - 1 AS created_day_index,
                       EXTRACT(hour FROM c.created_at AT TIME ZONE business.timezone_id)::integer AS created_hour,
                       c.owner_user_id, u.display_name AS user_name,
                       COALESCE(c.customer_id, '00000000-0000-0000-0000-000000000000'::uuid) AS customer_id, COALESCE(customer.name, 'Nie przypisano') AS customer_name, c.provider,
                       channel.name AS channel_name, c.status, c.workflow_status, c.resolution_category,
                       sla.first_response_completed_at, sla.first_response_due_at, sla.first_response_started_at, sla.first_response_stopped_at,
                       CASE
                           WHEN sla.first_response_due_at IS NULL THEN 'unmeasured'
                           WHEN sla.first_response_completed_at <= sla.first_response_due_at THEN 'met'
                           WHEN sla.first_response_completed_at > sla.first_response_due_at
                             OR sla.first_response_stopped_at >= sla.first_response_due_at THEN 'breached'
                           WHEN sla.first_response_stopped_at IS NOT NULL OR c.workflow_status = 'RESOLVED' THEN 'unmeasured'
                           WHEN business.observed_at >= sla.first_response_due_at THEN 'breached'
                           ELSE 'pending'
                       END AS first_response_outcome,
                       CASE WHEN response.historically_breached THEN 'BREACHED' ELSE sla.state END AS sla_state,
                       response.historically_breached OR COALESCE(history.breached, FALSE) AS historical_support_breach,
                       COALESCE(history.next_response_breached, FALSE) OR EXISTS (
                           SELECT 1 FROM case_next_response_cycles n
                           WHERE n.case_id = c.id AND (n.ended_at > n.due_at
                               OR (n.ended_at = n.due_at AND n.outcome <> 'RESPONDED'))
                       ) AS historical_next_response_breach,
                       active.kind AS operational_kind,
                       CASE
                           WHEN active.kind = 'FOLLOW_UP' AND active.due_at <= business.observed_at THEN 'FOLLOW_UP_DUE'
                           WHEN active.kind = 'FOLLOW_UP' THEN 'ON_TRACK'
                           WHEN active.due_at <= business.observed_at THEN 'BREACHED'
                           WHEN active.warning_at <= business.observed_at THEN 'WARNING'
                           WHEN active.id IS NOT NULL THEN 'ON_TRACK'
                       END AS operational_state,
                       CASE WHEN business.global_access OR responder.user_id = business.viewer_id
                            THEN responder.user_id END AS first_responder_id,
                       CASE WHEN business.global_access OR responder.user_id = business.viewer_id
                            THEN responder.display_name END AS first_responder_name,
                       resolution.wall_seconds, resolution.working_seconds,
                       COALESCE(c.status = 'RESOLVED' AND (
                           SELECT a.action = 'CASE_FORCE_RESOLVE' FROM audit_events a
                           WHERE a.case_id = c.id AND a.action IN ('CASE_RESOLVE', 'CASE_FORCE_RESOLVE')
                           ORDER BY occurred_at DESC, id DESC LIMIT 1
                       ), FALSE) AS force_resolved
                FROM cases c
                LEFT JOIN customers customer ON customer.id = c.customer_id
                JOIN channels channel ON channel.id = c.channel_id
                LEFT JOIN users u ON u.id = c.owner_user_id
                LEFT JOIN case_sla sla ON sla.case_id = c.id
                -- The projection is one row per Case. A bounded lookup prevents cold-catalog
                -- estimates for its singleton settings join multiplying the whole dashboard plan.
                JOIN LATERAL (
                    SELECT p.historically_breached FROM case_response_sla_projection p
                    WHERE p.case_id = c.id LIMIT 1
                ) response ON TRUE
                LEFT JOIN case_operational_deadline_projection active ON active.case_id = c.id
                LEFT JOIN LATERAL (
                    SELECT TRUE AS breached, bool_or(d.kind = 'NRT') AS next_response_breached
                    FROM case_operational_deadlines d
                    WHERE d.case_id = c.id AND d.kind IN ('FRT', 'NRT') AND d.breached_at IS NOT NULL
                    HAVING count(*) > 0
                ) history ON TRUE
                LEFT JOIN LATERAL (
                    SELECT m.author_user_id AS user_id, sender.display_name
                    FROM messages m
                    JOIN delivery_attempts a ON a.message_id = m.id
                    JOIN users sender ON sender.id = m.author_user_id
                    WHERE m.case_id = c.id AND m.kind = 'SUPPORT' AND NOT m.inbound
                      AND a.finished_at = sla.first_response_completed_at AND a.error_category IS NULL
                    ORDER BY m.id
                    LIMIT 1
                ) responder ON TRUE
                LEFT JOIN case_resolution_times resolution ON resolution.case_id = c.id AND resolution.ended_at = c.resolved_at
                CROSS JOIN (SELECT ?::text AS timezone_id, ?::timestamptz AS observed_at,
                                   ?::boolean AS global_access, ?::uuid AS viewer_id) business
                WHERE business.global_access OR c.owner_user_id = business.viewer_id
                ORDER BY c.created_at, c.id
                """;

    private final JdbcTemplate jdbc;
    private final StatisticsOverviewRepository statistics;

    AnalyticsRecordsController(JdbcTemplate jdbc, StatisticsOverviewRepository statistics) {
        this.jdbc = jdbc;
        this.statistics = statistics;
    }

    @GetMapping("/records")
    DashboardFacts records(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw ApiProblemException.authenticationRequired();
        UUID currentUser;
        try {
            currentUser = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.authenticationRequired();
        }
        boolean global = authentication.getAuthorities().stream()
                .anyMatch(value -> "view_global_statistics".equals(value.getAuthority()));
        String timezone = statistics.activeTimezone();
        Instant observedAt = jdbc.queryForObject("SELECT statement_timestamp()", OffsetDateTime.class).toInstant();
        List<Fact> facts = jdbc.query(RECORDS_SQL, this::mapFact, timezone, observedAt.atOffset(java.time.ZoneOffset.UTC), global, currentUser);
        List<UserOption> users = global
                ? jdbc.query("SELECT id, display_name FROM users WHERE active = TRUE ORDER BY display_name",
                        (rs, row) -> new UserOption(rs.getObject("id", UUID.class), rs.getString("display_name")))
                : jdbc.query("SELECT id, display_name FROM users WHERE id = ?",
                        (rs, row) -> new UserOption(rs.getObject("id", UUID.class), rs.getString("display_name")), currentUser);
        return new DashboardFacts(facts, users, observedAt, global, timezone);
    }

    private Fact mapFact(ResultSet rs, int row) throws SQLException {
        String status = rs.getString("status");
        String resolution = resolution(status, rs.getString("resolution_category"));
        String slaState = switch (rs.getString("sla_state") == null ? "ON_TRACK" : rs.getString("sla_state")) {
            case "BREACHED" -> "breached";
            case "WARNING" -> "warning";
            default -> "met";
        };
        return new Fact(
                rs.getObject("id", UUID.class), rs.getString("reference"), instant(rs, "created_at"),
                rs.getString("created_date"), rs.getString("claimed_date"), rs.getString("resolved_date"),
                rs.getInt("created_day_index"), rs.getInt("created_hour"), instant(rs, "claimed_at"),
                instant(rs, "first_response_completed_at"), instant(rs, "completed_at"),
                rs.getObject("owner_user_id", UUID.class), rs.getString("user_name"),
                rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getString("provider").toLowerCase(), rs.getString("channel_name"), status.toLowerCase(),
                statusLabel(status), rs.getString("workflow_status"), "medium", List.of(), resolution, slaState,
                instant(rs, "first_response_due_at"), rs.getBoolean("force_resolved"),
                rs.getObject("wall_seconds", Long.class), rs.getObject("working_seconds", Long.class),
                instant(rs, "first_response_started_at"), instant(rs, "first_response_stopped_at"),
                rs.getBoolean("historical_support_breach"), rs.getBoolean("historical_next_response_breach"),
                rs.getString("operational_kind"), rs.getString("operational_state"),
                rs.getObject("first_responder_id", UUID.class), rs.getString("first_responder_name"),
                rs.getString("first_response_outcome"));
    }

    private static String resolution(String status, String category) {
        if ("IGNORED".equals(status)) return "spam";
        if (!"RESOLVED".equals(status)) return null;
        return "NO_ACTION_REQUIRED".equals(category) ? "not_applicable" : "resolved";
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "NEW" -> "Nowe";
            case "VERIFICATION" -> "W weryfikacji";
            case "WAITING_FOR_CUSTOMER" -> "Oczekuje na klienta";
            case "PARTIALLY_IGNORED" -> "Częściowo zignorowane";
            case "IGNORED" -> "Zignorowane";
            case "RESOLVED" -> "Rozwiązane";
            default -> status;
        };
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record DashboardFacts(List<Fact> records, List<UserOption> users, Instant generatedAt, boolean global,
                          String reportingTimezone) { }
    record UserOption(UUID id, String fullName) { }
    record Fact(UUID id, String reference, Instant createdAt, String createdDate, String claimedDate, String resolvedDate,
                int createdDayIndex, int createdHour, Instant claimedAt, Instant firstResponseAt, Instant resolvedAt,
                UUID userId, String userName, UUID customerId, String customerName, String platform,
                String sourceChannel, String status, String statusLabel, String workflowStatus, String priority, List<String> tags,
                String resolutionReason, String slaState, Instant slaDeadlineAt,
                boolean excludedFromUserPerformance, Long resolutionWallSeconds, Long resolutionWorkingSeconds,
                Instant firstResponseStartedAt, Instant firstResponseStoppedAt, boolean historicalSupportBreach,
                boolean historicalNextResponseBreach, String operationalDeadlineKind, String operationalDeadlineState,
                UUID firstResponderId, String firstResponderName, String firstResponseOutcome) { }
}
