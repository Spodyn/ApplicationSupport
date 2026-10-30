package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorCodec;
import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.CursorPosition;
import com.unifiedsupportinbox.InvalidCursorException;
import com.unifiedsupportinbox.identity.UserRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only administrative projection; user presence is informational, not authorization. */
@Service
class AdminUserListService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CursorCodec cursors;
    private final PermissionService permissions;

    AdminUserListService(
            NamedParameterJdbcTemplate jdbc,
            CursorCodec cursors,
            PermissionService permissions) {
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    CursorPage<AdminUserItem> list(
            Authentication authentication,
            String search,
            UserRole role,
            Boolean active,
            String cursor,
            Integer requestedLimit) {
        UsiSessionPrincipal actor = principal(authentication);
        if (actor.role() != UserRole.ADMIN) {
            throw ApiProblemException.accessDenied();
        }
        permissions.requirePermission(actor, PermissionCatalog.MANAGE_USERS);

        String normalizedSearch = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        if (normalizedSearch.length() > 200) {
            throw ApiProblemException.validationFailed("Search must not exceed 200 characters.");
        }
        final int limit;
        try {
            limit = ApiV1Conventions.pageSize(requestedLimit);
        } catch (IllegalArgumentException invalidLimit) {
            throw ApiProblemException.validationFailed("Page size must be between 1 and 100.");
        }

        String scope = scope(actor.userId(), normalizedSearch, role, active);
        CursorPosition position = cursor == null ? null : cursors.decode(cursor, scope);
        if (position != null && !anchorStillExists(position)) {
            throw new InvalidCursorException(InvalidCursorException.Reason.SCOPE_MISMATCH);
        }

        StringBuilder sql = new StringBuilder("""
                SELECT u.id, u.email, u.display_name, u.role, u.active,
                       u.valid_from, u.valid_until, u.last_login_at, u.created_at,
                       (SELECT count(*) FROM cases c
                        WHERE c.owner_user_id = u.id
                          AND c.status NOT IN ('IGNORED', 'RESOLVED')) AS active_assigned_cases,
                       (u.active
                        AND (u.valid_from IS NULL OR u.valid_from <= CURRENT_TIMESTAMP)
                        AND (u.valid_until IS NULL OR u.valid_until > CURRENT_TIMESTAMP)
                        AND EXISTS (
                           SELECT 1 FROM spring_session s
                           WHERE s.principal_name = CAST(u.id AS varchar)
                             AND s.expiry_time > (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)
                             AND s.last_access_time >= (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000) - 300000
                       )) AS recently_online
                FROM users u
                WHERE 1 = 1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit + 1);

        if (!normalizedSearch.isEmpty()) {
            sql.append("""
                    AND (strpos(lower(u.display_name), :search) > 0
                         OR strpos(lower(u.email), :search) > 0)
                    """);
            params.addValue("search", normalizedSearch);
        }
        if (role != null) {
            sql.append(" AND u.role = :role\n");
            params.addValue("role", role.name());
        }
        if (active != null) {
            sql.append(" AND u.active = :active\n");
            params.addValue("active", active);
        }
        if (position != null) {
            sql.append(" AND (u.created_at, u.id) < (:createdAt, :cursorId)\n");
            params.addValue("createdAt", OffsetDateTime.ofInstant(position.sortValue(), ZoneOffset.UTC));
            params.addValue("cursorId", position.id());
        }
        sql.append(" ORDER BY u.created_at DESC, u.id DESC LIMIT :limit");

        List<AdminUserItem> fetched = jdbc.query(sql.toString(), params, (rs, row) -> map(rs));
        boolean hasMore = fetched.size() > limit;
        List<AdminUserItem> items = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore
                ? cursors.encode(
                        new CursorPosition(items.getLast().createdAt(), items.getLast().id()), scope)
                : null;
        return new CursorPage<>(items, nextCursor);
    }

    private boolean anchorStillExists(CursorPosition position) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users WHERE id = :id AND created_at = :createdAt
                """, new MapSqlParameterSource()
                .addValue("id", position.id())
                .addValue("createdAt", OffsetDateTime.ofInstant(position.sortValue(), ZoneOffset.UTC)),
                Integer.class);
        return count != null && count == 1;
    }

    private static AdminUserItem map(ResultSet rs) throws SQLException {
        return new AdminUserItem(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                UserRole.valueOf(rs.getString("role")),
                rs.getBoolean("active"),
                rs.getBoolean("recently_online") ? "online" : "offline",
                timestamp(rs, "valid_from"),
                timestamp(rs, "valid_until"),
                timestamp(rs, "last_login_at"),
                rs.getLong("active_assigned_cases"),
                timestamp(rs, "created_at"));
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String scope(UUID actorId, String search, UserRole role, Boolean active) {
        return "admin-users:v1:actor=" + actorId + ":search=" + search + ":role=" + role + ":active=" + active;
    }

    private static UsiSessionPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UsiSessionPrincipal principal)) {
            throw ApiProblemException.authenticationRequired();
        }
        return principal;
    }

    record AdminUserItem(
            UUID id,
            String email,
            String fullName,
            UserRole role,
            boolean active,
            String presence,
            Instant validFrom,
            Instant validUntil,
            Instant lastLoginAt,
            long activeAssignedCases,
            Instant createdAt) {
    }
}
