package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.identity.UserRole;
import java.nio.CharBuffer;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BootstrapAdminService {

    private final UserAccountRepository users;
    private final BootstrapAdminStateRepository bootstrapState;
    private final PasswordEncoder encoder;

    BootstrapAdminService(
            UserAccountRepository users,
            BootstrapAdminStateRepository bootstrapState,
            @Qualifier("bootstrapAdminPasswordEncoder") PasswordEncoder encoder) {
        this.users = users;
        this.bootstrapState = bootstrapState;
        this.encoder = encoder;
    }

    @Transactional
    UUID bootstrap(String email, String displayName, char[] password) {
        Objects.requireNonNull(password, "password");
        validatePassword(password);

        BootstrapAdminStateRepository.State state = bootstrapState.lockSingleton();
        if (state.consumed()) {
            throw new BootstrapAdminException("bootstrap administrator has already been consumed");
        }
        if (users.existsByRoleAndActiveTrue(UserRole.ADMIN)) {
            throw new BootstrapAdminException("an active administrator already exists");
        }

        String passwordHash = encoder.encode(CharBuffer.wrap(password));
        UserAccount administrator = users.saveAndFlush(new UserAccount(
                email,
                displayName,
                passwordHash,
                UserRole.ADMIN,
                true,
                null,
                null));
        bootstrapState.markConsumed(administrator.id());
        return administrator.id();
    }

    private static void validatePassword(char[] password) {
        int characterCount = Character.codePointCount(password, 0, password.length);
        if (characterCount < 12 || characterCount > 128) {
            throw new BootstrapAdminException(
                    "bootstrap administrator password must contain 12 to 128 Unicode characters");
        }
        for (char character : password) {
            if (character == '\n' || character == '\r' || character == '\0') {
                throw new BootstrapAdminException(
                        "bootstrap administrator password must contain exactly one text line");
            }
        }
    }
}
