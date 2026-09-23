package com.unifiedsupportinbox.readstate.internal;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes the v1 sparse unread contract for the viewers that existed when a Case was created.
 *
 * <p>An explicit row with a null read position means unread. Absence does not mean unread, which is
 * important for users created after historical Cases already existed.</p>
 */
@Service
class CaseCreatedUnreadProjection {

    private final JdbcTemplate jdbc;

    CaseCreatedUnreadProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    int initialize(UUID caseId) {
        Objects.requireNonNull(caseId, "caseId");

        if (!caseExists(caseId)) {
            throw new IllegalArgumentException("Case does not exist: " + caseId);
        }

        return jdbc.update("""
                INSERT INTO case_read_states (
                    user_id,
                    case_id,
                    last_read_message_id,
                    last_read_at,
                    updated_at
                )
                SELECT u.id,
                       c.id,
                       NULL,
                       NULL,
                       CURRENT_TIMESTAMP
                FROM cases c
                JOIN users u
                  ON u.created_at <= c.created_at
                 AND u.active = TRUE
                 AND u.role IN ('USER', 'ADMIN')
                 AND (u.valid_from IS NULL OR u.valid_from <= c.created_at)
                 AND (u.valid_until IS NULL OR u.valid_until > c.created_at)
                WHERE c.id = ?
                ON CONFLICT (user_id, case_id) DO NOTHING
                """, caseId);
    }

    private boolean caseExists(UUID caseId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM cases WHERE id = ?",
                Integer.class,
                caseId);
        return count != null && count == 1;
    }
}
