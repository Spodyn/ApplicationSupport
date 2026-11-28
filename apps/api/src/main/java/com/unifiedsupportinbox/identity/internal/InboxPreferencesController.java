package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account identity always comes from the authenticated session, never the body. */
@RestController
@RequestMapping("/api/v1/users/me/inbox-preferences")
class InboxPreferencesController {
    private final JdbcTemplate jdbc;

    InboxPreferencesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    Preferences get(Authentication authentication) {
        UUID userId = userId(authentication);
        return new Preferences(jdbc.query("SELECT mode FROM user_inbox_preferences WHERE user_id = ?",
                (rs, row) -> Mode.valueOf(rs.getString("mode")), userId).stream().findFirst().orElse(Mode.UNIFIED));
    }

    @PutMapping
    @Transactional
    Preferences save(@RequestBody Preferences input, Authentication authentication) {
        UUID userId = userId(authentication);
        if (input == null || input.mode() == null) {
            throw ApiProblemException.validationFailed("Inbox mode is required.");
        }
        jdbc.update("""
                INSERT INTO user_inbox_preferences(user_id, mode) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET mode = EXCLUDED.mode
                WHERE user_inbox_preferences.mode IS DISTINCT FROM EXCLUDED.mode
                """, userId, input.mode().name());
        return input;
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UsiSessionPrincipal principal)) {
            throw ApiProblemException.authenticationRequired();
        }
        return principal.userId();
    }

    enum Mode { UNIFIED, SEPARATE_BY_PLATFORM }
    record Preferences(Mode mode) {}
}
