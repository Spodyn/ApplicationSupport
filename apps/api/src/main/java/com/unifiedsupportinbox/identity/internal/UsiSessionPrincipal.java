package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.identity.UserRole;
import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

record UsiSessionPrincipal(
        UUID userId,
        String email,
        String displayName,
        UserRole role,
        Instant createdAt) implements Serializable, Principal {

    @Serial
    private static final long serialVersionUID = 1L;

    static UsiSessionPrincipal from(UserAccount user) {
        return new UsiSessionPrincipal(
                user.id(),
                user.email(),
                user.displayName(),
                user.role(),
                user.createdAt());
    }

    @Override
    public String getName() {
        return userId.toString();
    }

    List<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
