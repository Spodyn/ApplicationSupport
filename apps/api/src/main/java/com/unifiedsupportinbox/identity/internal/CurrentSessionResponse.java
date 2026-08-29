package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.identity.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record CurrentSessionResponse(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        Instant createdAt,
        List<String> effectivePermissions) {

    static CurrentSessionResponse from(UsiSessionPrincipal principal) {
        return new CurrentSessionResponse(
                principal.userId(),
                principal.email(),
                principal.displayName(),
                principal.role(),
                principal.createdAt(),
                List.of());
    }
}
