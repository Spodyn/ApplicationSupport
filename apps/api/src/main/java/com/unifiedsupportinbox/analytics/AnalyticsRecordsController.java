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

    private final JdbcTemplate jdbc;

    AnalyticsRecordsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
        List<Fact> facts = jdbc.query("""
                SELECT c.id, c.reference, c.created_at, c.claimed_at,
                       COALESCE(c.resolved_at, c.ignored_at) AS completed_at,
                       (c.created_at AT TIME ZONE business.timezone_id)::date::text AS created_date,
                       (c.claimed_at AT TIME ZONE business.timezone_id)::date::text AS claimed_date,
                       (COALESCE(c.resolved_at, c.ignored_at) AT TIME ZONE business.timezone_id)::date::text AS resolved_date,
                       EXTRACT(isodow FROM c.created_at AT TIME ZONE business.timezone_id)::integer - 1 AS created_day_index,
                       EXTRACT(hour FROM c.created_at AT TIME ZONE business.timezone_id)::integer AS created_hour,
                       c.owner_user_id, u.display_name AS user_name,
                       COALESCE(c.customer_id, '00000000-0000-0000-0000-000000000000'::uuid) AS customer_id, COALESCE(customer.name, 'Nie przypisano') AS customer_name, c.provider,
                       channel.name AS channel_name, c.status, c.resolution_category,
                       sla.first_response_completed_at, sla.first_response_due_at, sla.state AS sla_state,
                       EXISTS (SELECT 1 FROM audit_events a WHERE a.case_id = c.id AND a.action = 'CASE_FORCE_RESOLVE') AS force_resolved
                FROM cases c
                LEFT JOIN customers customer ON customer.id = c.customer_id
                JOIN channels channel ON channel.id = c.channel_id
                LEFT JOIN users u ON u.id = c.owner_user_id
                LEFT JOIN case_sla sla ON sla.case_id = c.id
                CROSS JOIN business_hours business
                WHERE (? OR c.owner_user_id = ?)
                  AND business.active = TRUE
                ORDER BY c.created_at, c.id
                """, this::mapFact, global, currentUser);
        List<UserOption> users = global
                ? jdbc.query("SELECT id, display_name FROM users WHERE active = TRUE ORDER BY display_name",
                        (rs, row) -> new UserOption(rs.getObject("id", UUID.class), rs.getString("display_name")))
                : jdbc.query("SELECT id, display_name FROM users WHERE id = ?",
                        (rs, row) -> new UserOption(rs.getObject("id", UUID.class), rs.getString("display_name")), currentUser);
        return new DashboardFacts(facts, users, Instant.now(), global);
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
                statusLabel(status), "medium", List.of(), resolution, slaState,
                instant(rs, "first_response_due_at"), rs.getBoolean("force_resolved"));
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

    record DashboardFacts(List<Fact> records, List<UserOption> users, Instant generatedAt, boolean global) { }
    record UserOption(UUID id, String fullName) { }
    record Fact(UUID id, String reference, Instant createdAt, String createdDate, String claimedDate, String resolvedDate,
                int createdDayIndex, int createdHour, Instant claimedAt, Instant firstResponseAt, Instant resolvedAt,
                UUID userId, String userName, UUID customerId, String customerName, String platform,
                String sourceChannel, String status, String statusLabel, String priority, List<String> tags,
                String resolutionReason, String slaState, Instant slaDeadlineAt,
                boolean excludedFromUserPerformance) { }
}
