package com.unifiedsupportinbox.readstate.internal;

import com.unifiedsupportinbox.ApiProblemException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Updates only the caller's personal cursor and never moves it backwards. */
@Service
class CaseReadPositionService {

    private final JdbcTemplate jdbc;

    CaseReadPositionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    ReadPosition markRead(UUID caseId, UUID userId, UUID messageId) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(messageId, "messageId");
        requireEligibleUser(userId);
        requireMessageInCase(caseId, messageId);

        jdbc.update("""
                INSERT INTO case_read_states (
                    user_id, case_id, last_read_message_id, last_read_at, updated_at
                ) VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, case_id) DO UPDATE
                SET last_read_message_id = EXCLUDED.last_read_message_id,
                    last_read_at = EXCLUDED.last_read_at,
                    updated_at = EXCLUDED.updated_at
                WHERE case_read_states.last_read_message_id IS NULL
                   OR EXISTS (
                       SELECT 1
                       FROM messages proposed
                       JOIN messages current
                         ON current.id = case_read_states.last_read_message_id
                       WHERE proposed.id = EXCLUDED.last_read_message_id
                         AND (
                             COALESCE(proposed.provider_created_at, proposed.created_at)
                               > COALESCE(current.provider_created_at, current.created_at)
                             OR (
                                 COALESCE(proposed.provider_created_at, proposed.created_at)
                                   = COALESCE(current.provider_created_at, current.created_at)
                                 AND proposed.id > current.id
                             )
                         )
                   )
                """, userId, caseId, messageId);

        return jdbc.query("""
                SELECT last_read_message_id, last_read_at
                FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, (resultSet, row) -> new ReadPosition(
                caseId, resultSet.getObject("last_read_message_id", UUID.class),
                resultSet.getTimestamp("last_read_at").toInstant()), userId, caseId)
                .stream().findFirst().orElseThrow();
    }

    private void requireEligibleUser(UUID userId) {
        Integer eligible = jdbc.queryForObject("""
                SELECT count(*)
                FROM users
                WHERE id = ?
                  AND active = TRUE
                  AND role IN ('USER', 'ADMIN')
                  AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
                  AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, Integer.class, userId);
        if (eligible == null || eligible != 1) throw ApiProblemException.accessDenied();
    }

    private void requireMessageInCase(UUID caseId, UUID messageId) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE id = ? AND case_id = ?",
                Integer.class, messageId, caseId);
        if (found == null || found != 1) {
            throw ApiProblemException.notFound("Message was not found in the requested Case.");
        }
    }

    record ReadPosition(UUID caseId, UUID messageId, Instant readAt) {}
}
