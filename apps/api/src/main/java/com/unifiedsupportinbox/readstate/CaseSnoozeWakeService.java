package com.unifiedsupportinbox.readstate;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records the personal effect of a shared Case transition. */
@Service
public class CaseSnoozeWakeService {

    private final JdbcTemplate jdbc;

    public CaseSnoozeWakeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void wake(UUID caseId) {
        jdbc.query("SELECT id FROM cases WHERE id = ? FOR UPDATE", rs -> { }, caseId);
        jdbc.update("""
                WITH removed AS (
                    DELETE FROM case_snoozes WHERE case_id = ?
                    RETURNING case_id, user_id, created_at, until_at
                )
                INSERT INTO case_snooze_history (case_id, user_id, action, snoozed_at, until_at)
                SELECT case_id, user_id, 'CASE_SNOOZE_WOKEN', created_at, until_at FROM removed
                """, caseId);
    }
}
