package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.identity.BrowserSessionAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class JdbcBrowserSessionAccess implements BrowserSessionAccess {
    private final JdbcTemplate jdbc;
    JdbcBrowserSessionAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean isValid(String sessionId, String principalName) {
        if (sessionId == null || principalName == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM spring_session s JOIN users u ON u.id::text = s.principal_name
                    WHERE s.session_id = ? AND s.principal_name = ? AND s.expiry_time > ?
                      AND u.active = TRUE AND u.password_hash IS NOT NULL
                      AND (u.valid_from IS NULL OR u.valid_from <= now())
                      AND (u.valid_until IS NULL OR u.valid_until > now())
                )
                """, Boolean.class, sessionId, principalName, System.currentTimeMillis()));
    }
}
