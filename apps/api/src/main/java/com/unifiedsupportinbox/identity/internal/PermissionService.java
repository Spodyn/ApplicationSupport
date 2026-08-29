package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.identity.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PermissionService {

    private final UserAccountRepository users;
    private final PermissionStore permissions;

    PermissionService(UserAccountRepository users, PermissionStore permissions) {
        this.users = users;
        this.permissions = permissions;
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
        if (!hasPermission(actor, permission)) {
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

        List<String> normalized;
        try {
            normalized = PermissionCatalog.normalizeRequested(requestedPermissions);
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed("One or more permission codes are invalid.");
        }

        permissions.replaceExplicitPermissions(userId, normalized);
        return new PermissionSnapshot(
                userId,
                permissions.explicitPermissions(userId),
                effectivePermissions(target));
    }

    List<String> effectivePermissions(UserAccount user) {
        if (!user.isSessionEligibleAt(Instant.now())) {
            return List.of();
        }
        if (user.role() == UserRole.ADMIN) {
            return PermissionCatalog.ALL;
        }
        return permissions.explicitPermissions(user.id());
    }

    record PermissionSnapshot(
            UUID userId,
            List<String> explicitPermissions,
            List<String> effectivePermissions) {
    }
}
