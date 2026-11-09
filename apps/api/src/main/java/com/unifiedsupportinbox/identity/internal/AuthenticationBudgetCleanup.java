package com.unifiedsupportinbox.identity.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class AuthenticationBudgetCleanup {
    private final JdbcTemplate jdbc;
    AuthenticationBudgetCleanup(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    void removeExpired() {
        // Bounded cleanup; another instance may safely perform the same sweep.
        jdbc.update("""
                DELETE FROM authentication_abuse_budgets WHERE bucket_key IN (
                    SELECT bucket_key FROM authentication_abuse_budgets
                    WHERE expires_at < now() - interval '1 hour' LIMIT 1000
                    FOR UPDATE SKIP LOCKED)
                """);
    }
}
