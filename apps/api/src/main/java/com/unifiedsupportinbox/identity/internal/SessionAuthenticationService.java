package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import java.time.Instant;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SessionAuthenticationService {

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;

    SessionAuthenticationService(
            UserAccountRepository users,
            PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    UsiSessionPrincipal authenticate(String suppliedEmail, String suppliedPassword) {
        String email = normalizeEmail(suppliedEmail);
        if (email == null || !PasswordPolicy.acceptsAuthenticationInput(suppliedPassword)) {
            throw ApiProblemException.invalidCredentials();
        }

        UserAccount user = users.findByEmailIgnoreCase(email)
                .orElseThrow(ApiProblemException::invalidCredentials);

        String storedHash = user.passwordHash();
        boolean matches = storedHash != null
                && encoder.matches(suppliedPassword, storedHash);
        Instant now = Instant.now();

        if (!matches || !user.isSessionEligibleAt(now)) {
            throw ApiProblemException.invalidCredentials();
        }

        if (encoder.upgradeEncoding(storedHash)) {
            user.setPasswordHash(encoder.encode(suppliedPassword));
        }
        user.recordSuccessfulLogin(now);
        return UsiSessionPrincipal.from(user);
    }

    private static String normalizeEmail(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return normalized.length() >= 3 && normalized.length() <= 320 ? normalized : null;
    }
}
