package com.unifiedsupportinbox.readstate.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.OutboxEventStore;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Updates only the caller's personal cursor and never moves it backwards. */
@Service
class CaseReadPositionService {

    static final String OUTBOX_TYPE = "case.read_position_changed";
    private static final String AGGREGATE_TYPE = "case";

    private final JdbcTemplate jdbc;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    CaseReadPositionService(JdbcTemplate jdbc, OutboxEventStore outbox, ObjectMapper json) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    ReadPosition markRead(UUID caseId, UUID userId, UUID messageId) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(messageId, "messageId");
        requireEligibleUser(userId);
        requireMessageInCase(caseId, messageId);

        int changed = jdbc.update("""
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

        ReadPosition position = jdbc.query("""
                SELECT last_read_message_id, last_read_at
                FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, (resultSet, row) -> new ReadPosition(
                caseId, resultSet.getObject("last_read_message_id", UUID.class),
                resultSet.getTimestamp("last_read_at").toInstant()), userId, caseId)
                .stream().findFirst().orElseThrow();

        if (changed == 1) {
            outbox.append(
                    OUTBOX_TYPE,
                    AGGREGATE_TYPE,
                    caseId,
                    payload(caseId, userId, position.messageId()),
                    correlationId());
        }
        return position;
    }

    private String payload(UUID caseId, UUID userId, UUID messageId) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("userId", userId.toString());
        payload.put("messageId", messageId.toString());
        return payload.toString();
    }

    private static String correlationId() {
        String value = MDC.get("correlationId");
        return value == null || value.isBlank() || value.length() > 128
                ? UUID.randomUUID().toString()
                : value;
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
