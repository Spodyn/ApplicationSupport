package com.unifiedsupportinbox.identity.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Central policy used by authentication/session code to decide whether a
 * canonical user may create or retain a server-side session at a point in time.
 */
@Component
class UserSessionEligibilityPolicy {

    private final UserAccountRepository users;

    UserSessionEligibilityPolicy(UserAccountRepository users) {
        this.users = users;
    }

    boolean isSessionAllowed(UUID userId, Instant instant) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(instant, "instant");
        return users.findById(userId)
                .map(user -> user.isSessionEligibleAt(instant))
                .orElse(false);
    }
}
