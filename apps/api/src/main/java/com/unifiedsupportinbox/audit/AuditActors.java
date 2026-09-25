package com.unifiedsupportinbox.audit;

import java.util.UUID;

import org.springframework.security.core.Authentication;

/** Converts authenticated request context into the stable identifiers audit history needs. */
public final class AuditActors {

    private AuditActors() {
    }

    public static AuditActorType type(Authentication actor) {
        boolean admin = actor != null && actor.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return admin ? AuditActorType.ADMIN : AuditActorType.USER;
    }

    public static UUID userId(Authentication actor) {
        if (actor == null || actor.getName() == null) return null;
        try {
            return UUID.fromString(actor.getName());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
