package com.unifiedsupportinbox;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
final class IdempotencyStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    IdempotencyStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<UUID> tryCreate(UUID userId, String commandScope, String key, String requestHash) {
        List<UUID> ids = jdbc.query(
                """
                INSERT INTO idempotency_keys (user_id, command_scope, "key", request_hash)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, command_scope, "key") DO NOTHING
                RETURNING id
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                userId,
                commandScope,
                key,
                requestHash);
        return ids.stream().findFirst();
    }

    Optional<StoredIdempotency> find(UUID userId, String commandScope, String key) {
        List<StoredIdempotency> rows = jdbc.query(
                """
                SELECT request_hash, response_status, response_body
                FROM idempotency_keys
                WHERE user_id = ? AND command_scope = ? AND "key" = ?
                """,
                (resultSet, rowNumber) -> {
                    Number status = (Number) resultSet.getObject("response_status");
                    return new StoredIdempotency(
                            resultSet.getString("request_hash"),
                            status == null ? null : status.intValue(),
                            parseBody(resultSet.getString("response_body")));
                },
                userId,
                commandScope,
                key);
        return rows.stream().findFirst();
    }

    boolean deleteIfExpired(UUID userId, String commandScope, String key) {
        return jdbc.update(
                """
                DELETE FROM idempotency_keys
                WHERE user_id = ?
                  AND command_scope = ?
                  AND "key" = ?
                  AND expires_at <= CURRENT_TIMESTAMP
                """,
                userId,
                commandScope,
                key) == 1;
    }

    int deleteExpired() {
        return jdbc.update("DELETE FROM idempotency_keys WHERE expires_at <= CURRENT_TIMESTAMP");
    }

    void complete(UUID id, IdempotencyResponse response) {
        int updated = jdbc.update(
                """
                UPDATE idempotency_keys
                SET response_status = ?, response_body = CAST(? AS jsonb)
                WHERE id = ? AND response_status IS NULL AND response_body IS NULL
                """,
                response.status(),
                response.body().toString(),
                id);
        if (updated != 1) {
            throw new IllegalStateException("idempotency response could not be finalized");
        }
    }

    private JsonNode parseBody(String body) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored idempotency response is invalid JSON", exception);
        }
    }

    record StoredIdempotency(String requestHash, Integer responseStatus, JsonNode responseBody) {
    }
}
