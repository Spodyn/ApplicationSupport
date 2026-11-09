package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.identity.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
class PermissionService {

    private final UserAccountRepository users;
    private final PermissionStore permissions;
    private final AuditEventStore auditEvents;
    private final JdbcTemplate jdbc;

    PermissionService(UserAccountRepository users, PermissionStore permissions, AuditEventStore auditEvents, JdbcTemplate jdbc) {
        this.users = users;
        this.permissions = permissions;
        this.auditEvents = auditEvents;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    List<String> effectivePermissions(UUID userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> ApiProblemException.notFound("User was not found."));
        return effectivePermissions(user);
    }

    @Transactional(readOnly = true)
    boolean hasPermission(UsiSessionPrincipal actor, String permission) {
        return actor != null && effectivePermissions(actor.userId()).contains(permission);
    }

    @Transactional(readOnly = true)
    void requirePermission(UsiSessionPrincipal actor, String permission) {
        if (actor == null || actor.role() != UserRole.ADMIN || !hasPermission(actor, permission)) {
            throw ApiProblemException.accessDenied();
        }
    }

    @Transactional
    PermissionSnapshot replaceExplicitPermissions(
            UsiSessionPrincipal actor,
            UUID userId,
            List<String> requestedPermissions) {
        requirePermission(actor, PermissionCatalog.MANAGE_USERS);

        UserAccount target = users.findById(userId)
                .orElseThrow(() -> ApiProblemException.notFound("User was not found."));

        if (target.role() != UserRole.ADMIN) {
            throw ApiProblemException.validationFailed("Only administrators can receive administrative permissions.");
        }

        List<String> normalized;
        try {
            normalized = PermissionCatalog.normalizeRequested(requestedPermissions);
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed("One or more permission codes are invalid.");
        }

        List<String> previous = permissions.explicitPermissions(userId);
        jdbc.queryForList("SELECT id FROM users WHERE role = 'ADMIN' FOR UPDATE", UUID.class);
        permissions.replaceExplicitPermissions(userId, normalized);
        Integer managers = jdbc.queryForObject("""
                SELECT count(*) FROM users u JOIN user_permissions p ON p.user_id = u.id
                WHERE u.role = 'ADMIN' AND u.active = TRUE AND p.permission_code = 'manage_users'
                  AND (u.valid_from IS NULL OR u.valid_from <= now())
                  AND (u.valid_until IS NULL OR u.valid_until > now())
                """, Integer.class);
        if (managers == null || managers == 0) {
            throw ApiProblemException.conflict("At least one active administrator with user-management permission is required.");
        }
        auditEvents.append(
                actor.role() == UserRole.ADMIN ? AuditActorType.ADMIN : AuditActorType.USER,
                actor.userId(),
                "PERMISSIONS_UPDATED",
                "USER",
                userId,
                null,
                java.util.Map.of("previousPermissions", previous, "newPermissions", normalized));
        return new PermissionSnapshot(
                userId,
                permissions.explicitPermissions(userId),
                effectivePermissions(target));
    }

    List<String> effectivePermissions(UserAccount user) {
        if (!user.isSessionEligibleAt(Instant.now())) {
            return List.of();
        }
        return user.role() == UserRole.ADMIN
                ? permissions.explicitPermissions(user.id())
                : List.of();
    }

    record PermissionSnapshot(
            UUID userId,
            List<String> explicitPermissions,
            List<String> effectivePermissions) {
    }
}
