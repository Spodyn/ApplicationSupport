package com.unifiedsupportinbox.readstate.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class CaseSnoozeService {

    static final String SNOOZED_OUTBOX_TYPE = "case.snoozed";
    static final String CANCELLED_OUTBOX_TYPE = "case.snooze_cancelled";
    private static final Duration MIN_DURATION = Duration.ofMinutes(5);
    private static final Duration MAX_DURATION = Duration.ofDays(30);
    private static final String AGGREGATE_TYPE = "case";

    private final JdbcTemplate jdbc;
    private final IdempotentCommandExecutor idempotency;
    private final CaseReadPositionService readPositions;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    CaseSnoozeService(
            JdbcTemplate jdbc,
            IdempotentCommandExecutor idempotency,
            CaseReadPositionService readPositions,
            OutboxEventStore outbox,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.readPositions = readPositions;
        this.outbox = outbox;
        this.json = json;
    }

    IdempotencyResult snooze(
            UUID caseId,
            UUID userId,
            Instant until,
            String idempotencyKey,
            String correlationId) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(until, "until");
        Instant now = Instant.now();
        Duration duration = Duration.between(now, until);
        if (duration.compareTo(MIN_DURATION) < 0 || duration.compareTo(MAX_DURATION) > 0) {
            throw ApiProblemException.validationFailed("Snooze must be between 5 minutes and 30 days in the future.");
        }
        requireCorrelationId(correlationId);

        return idempotency.execute(
                userId,
                "case.snooze:" + caseId,
                idempotencyKey,
                Map.of("caseId", caseId.toString(), "until", until.toString()),
                () -> executeSnooze(caseId, userId, until, correlationId));
    }

    IdempotencyResult cancel(
            UUID caseId,
            UUID userId,
            String idempotencyKey,
            String correlationId) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(userId, "userId");
        requireCorrelationId(correlationId);
        return idempotency.execute(
                userId,
                "case.snooze-cancel:" + caseId,
                idempotencyKey,
                Map.of("caseId", caseId.toString()),
                () -> executeCancel(caseId, userId, correlationId));
    }

    private IdempotencyResponse executeSnooze(
            UUID caseId,
            UUID userId,
            Instant until,
            String correlationId) {
        requireActiveUser(userId);
        requireSnoozableCase(caseId);

        UUID latestMessageId = jdbc.query("""
                SELECT id
                FROM messages
                WHERE case_id = ?
                ORDER BY COALESCE(provider_created_at, created_at) DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), caseId)
                .stream().findFirst().orElse(null);
        if (latestMessageId != null) {
            readPositions.markRead(caseId, userId, latestMessageId);
        }

        jdbc.update("""
                INSERT INTO case_snoozes (case_id, user_id, until_at, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (case_id, user_id) DO UPDATE
                SET until_at = EXCLUDED.until_at,
                    created_at = CURRENT_TIMESTAMP
                """, caseId, userId, Timestamp.from(until));

        outbox.append(
                SNOOZED_OUTBOX_TYPE,
                AGGREGATE_TYPE,
                caseId,
                payload(caseId, userId, until),
                correlationId);

        ObjectNode response = json.createObjectNode();
        response.put("caseId", caseId.toString());
        response.put("snoozedUntil", until.toString());
        return new IdempotencyResponse(200, response);
    }

    private IdempotencyResponse executeCancel(UUID caseId, UUID userId, String correlationId) {
        requireActiveUser(userId);
        requireCaseExists(caseId);
        int deleted = jdbc.update(
                "DELETE FROM case_snoozes WHERE case_id = ? AND user_id = ?",
                caseId,
                userId);
        if (deleted == 1) {
            outbox.append(
                    CANCELLED_OUTBOX_TYPE,
                    AGGREGATE_TYPE,
                    caseId,
                    payload(caseId, userId, null),
                    correlationId);
        }

        ObjectNode response = json.createObjectNode();
        response.put("caseId", caseId.toString());
        response.put("cancelled", deleted == 1);
        return new IdempotencyResponse(200, response);
    }

    private void requireActiveUser(UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM users
                WHERE id = ?
                  AND active = TRUE
                  AND role IN ('USER', 'ADMIN')
                  AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
                  AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, Integer.class, userId);
        if (count == null || count != 1) throw ApiProblemException.accessDenied();
    }

    private void requireSnoozableCase(UUID caseId) {
        String status = jdbc.query(
                "SELECT status FROM cases WHERE id = ?",
                (rs, rowNum) -> rs.getString("status"),
                caseId).stream().findFirst().orElseThrow(() -> ApiProblemException.notFound("Case was not found."));
        if ("RESOLVED".equals(status) || "IGNORED".equals(status)) {
            throw ApiProblemException.conflict("Terminal Cases cannot be snoozed.");
        }
    }

    private void requireCaseExists(UUID caseId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM cases WHERE id = ?", Integer.class, caseId);
        if (count == null || count != 1) throw ApiProblemException.notFound("Case was not found.");
    }

    private String payload(UUID caseId, UUID userId, Instant until) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("userId", userId.toString());
        if (until != null) payload.put("snoozedUntil", until.toString());
        return payload.toString();
    }

    private static void requireCorrelationId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("correlationId must contain 1 to 128 characters");
        }
    }
}
