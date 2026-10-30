package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.identity.UserRole;
import java.time.Instant;
import java.util.List;

/**
 * Request for ADMIN-created accounts. No password hash, actor, system timestamp
 * or permission-derived authorization is ever accepted from the browser.
 */
public record AdminUserCreateRequest(
        String email,
        String fullName,
        UserRole role,
        Boolean active,
        Instant validFrom,
        Instant validUntil,
        List<String> permissions,
        String initialPassword) {

    @Override
    public String toString() {
        return "AdminUserCreateRequest[REDACTED]";
    }
}
