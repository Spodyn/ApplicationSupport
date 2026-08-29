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

    @Transactional
    PermissionSnapshot replaceExplicitPermissions(
            UsiSessionPrincipal actor,
            UUID userId,
            List<String> requestedPermissions) {
        if (actor == null || actor.role() != UserRole.ADMIN) {
            throw ApiProblemException.accessDenied();
        }

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

    private List<String> effectivePermissions(UserAccount user) {
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
