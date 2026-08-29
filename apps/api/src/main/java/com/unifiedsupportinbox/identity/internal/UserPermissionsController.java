package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
class UserPermissionsController {

    private final PermissionService permissions;

    UserPermissionsController(PermissionService permissions) {
        this.permissions = permissions;
    }

    @PutMapping("/{userId}/permissions")
    UserPermissionsResponse updatePermissions(
            @PathVariable UUID userId,
            @Valid @RequestBody PermissionUpdateRequest input,
            Authentication authentication) {
        UsiSessionPrincipal actor = principal(authentication);
        return UserPermissionsResponse.from(
                permissions.replaceExplicitPermissions(actor, userId, input.permissions()));
    }

    private static UsiSessionPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UsiSessionPrincipal principal)) {
            throw ApiProblemException.authenticationRequired();
        }
        return principal;
    }

    record PermissionUpdateRequest(@NotNull List<String> permissions) {
    }

    record UserPermissionsResponse(
            UUID userId,
            List<String> explicitPermissions,
            List<String> effectivePermissions) {

        static UserPermissionsResponse from(PermissionService.PermissionSnapshot snapshot) {
            return new UserPermissionsResponse(
                    snapshot.userId(),
                    snapshot.explicitPermissions(),
                    snapshot.effectivePermissions());
        }
    }
}
