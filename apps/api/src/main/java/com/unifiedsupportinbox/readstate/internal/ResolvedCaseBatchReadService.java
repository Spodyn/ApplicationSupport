package com.unifiedsupportinbox.readstate.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class ResolvedCaseBatchReadService {

    private static final String COMMAND_SCOPE = "case.read.mark-resolved";

    private final JdbcTemplate jdbc;
    private final IdempotentCommandExecutor idempotency;
    private final ObjectMapper json;

    ResolvedCaseBatchReadService(
            JdbcTemplate jdbc,
            IdempotentCommandExecutor idempotency,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.json = json;
    }

    IdempotencyResult markResolvedRead(UUID userId, String idempotencyKey) {
        Objects.requireNonNull(userId, "userId");
        requireEligibleUser(userId);
        return idempotency.execute(
                userId,
                COMMAND_SCOPE,
                idempotencyKey,
                Map.of("userId", userId.toString()),
                () -> execute(userId));
    }

    private IdempotencyResponse execute(UUID userId) {
        List<UpdatedReadPosition> updated = jdbc.query("""
                WITH latest_message AS (
                    SELECT DISTINCT ON (message.case_id)
                           message.case_id,
                           message.id AS message_id,
                           COALESCE(message.provider_created_at, message.created_at) AS message_order_at
                    FROM messages message
                    ORDER BY message.case_id,
                             COALESCE(message.provider_created_at, message.created_at) DESC,
                             message.id DESC
                ), candidates AS (
                    SELECT state.case_id, latest.message_id
                    FROM case_read_states state
                    JOIN cases current_case ON current_case.id = state.case_id
                    JOIN latest_message latest ON latest.case_id = state.case_id
                    LEFT JOIN messages current_message ON current_message.id = state.last_read_message_id
                    WHERE state.user_id = ?
                      AND current_case.status = 'RESOLVED'
                      AND (
                          state.last_read_message_id IS NULL
                          OR latest.message_order_at > COALESCE(current_message.provider_created_at, current_message.created_at)
                          OR (
                              latest.message_order_at = COALESCE(current_message.provider_created_at, current_message.created_at)
                              AND latest.message_id > current_message.id
                          )
                      )
                )
                UPDATE case_read_states state
                SET last_read_message_id = candidates.message_id,
                    last_read_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE state.user_id = ?
                  AND state.case_id = candidates.case_id
                RETURNING state.case_id, state.last_read_message_id
                """, (rs, rowNum) -> new UpdatedReadPosition(
                rs.getObject("case_id", UUID.class),
                rs.getObject("last_read_message_id", UUID.class)),
                userId,
                userId);

        ObjectNode response = json.createObjectNode();
        response.put("updatedCount", updated.size());
        return new IdempotencyResponse(200, response);
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
        if (eligible == null || eligible != 1) {
            throw ApiProblemException.accessDenied();
        }
    }

    private record UpdatedReadPosition(UUID caseId, UUID messageId) {}
}
