package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.identity.UserRole;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Idempotent, server-weighted Ignore voting command. */
@Service
class CaseIgnoreService {

    private final JdbcTemplate jdbc;
    private final IdempotentCommandExecutor idempotency;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    CaseIgnoreService(
            JdbcTemplate jdbc,
            IdempotentCommandExecutor idempotency,
            AuditEventStore audit,
            OutboxEventStore outbox,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
    }

    IdempotencyResult ignore(
            UUID caseId,
            UUID userId,
            String reason,
            String idempotencyKey,
            String correlationId) {
        String normalizedReason = optionalText(reason, 500);
        return idempotency.execute(
                Objects.requireNonNull(userId),
                "case.ignore:" + Objects.requireNonNull(caseId),
                idempotencyKey,
                Map.of("caseId", caseId.toString(), "reason", normalizedReason == null ? "" : normalizedReason),
                () -> execute(caseId, userId, normalizedReason, correlationId));
    }

    private IdempotencyResponse execute(UUID caseId, UUID userId, String reason, String correlationId) {
        Actor actor = actor(userId);
        Snapshot before = jdbc.query("""
                SELECT status, version
                FROM cases
                WHERE id = ?
                FOR UPDATE
                """, (rs, row) -> new Snapshot(rs.getString("status"), rs.getLong("version")), caseId)
                .stream().findFirst()
                .orElseThrow(() -> ApiProblemException.notFound("Case was not found."));
        if (!"NEW".equals(before.status()) && !"PARTIALLY_IGNORED".equals(before.status())) {
            throw ApiProblemException.conflict("Ignore is not available for this Case.");
        }
        Boolean activeVote = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM case_ignore_votes
                    WHERE case_id = ? AND user_id = ? AND active
                )
                """, Boolean.class, caseId, userId);
        if (Boolean.TRUE.equals(activeVote)) {
            throw ApiProblemException.conflict("The current user already has an active Ignore vote for this Case.");
        }
        Integer points = jdbc.queryForObject("""
                SELECT COALESCE(SUM(weight), 0)::int
                FROM case_ignore_votes
                WHERE case_id = ? AND active
                """, Integer.class, caseId);
        int score = Math.min(2, (points == null ? 0 : points) + actor.weight());
        String status = score >= 2 ? "IGNORED" : "PARTIALLY_IGNORED";

        jdbc.update("""
                INSERT INTO case_ignore_votes (case_id, user_id, weight, active)
                VALUES (?, ?, ?, TRUE)
                """, caseId, userId, actor.weight());
        Long version = jdbc.queryForObject("""
                UPDATE cases
                SET status = ?,
                    ignored_at = CASE WHEN ? = 'IGNORED' THEN statement_timestamp() ELSE NULL END,
                    updated_at = statement_timestamp(),
                    last_activity_at = statement_timestamp(),
                    version = version + 1
                WHERE id = ? AND version = ?
                RETURNING version
                """, Long.class, status, status, caseId, before.version());
        if (version == null) throw ApiProblemException.conflict("The Case changed before the vote was recorded.");
        if ("IGNORED".equals(status)) jdbc.update("DELETE FROM case_snoozes WHERE case_id = ?", caseId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", "IGNORE");
        metadata.put("previousStatus", before.status());
        metadata.put("status", status);
        metadata.put("weight", actor.weight());
        metadata.put("score", score);
        metadata.put("version", version);
        if (reason != null) metadata.put("reason", reason);
        audit.append(actor.role() == UserRole.ADMIN ? AuditActorType.ADMIN : AuditActorType.USER,
                userId, "CASE_IGNORE", "CASE", caseId, caseId, Map.copyOf(metadata));
        outbox.append("case.updated", "case", caseId,
                payload(caseId, status, score, version), correlationId);

        ObjectNode response = json.createObjectNode();
        response.put("caseId", caseId.toString());
        response.put("status", status);
        response.put("ignoreScore", score);
        response.put("version", version);
        return new IdempotencyResponse(200, response);
    }

    private Actor actor(UUID userId) {
        return jdbc.query("""
                SELECT u.role,
                       COALESCE((
                           SELECT w.weight
                           FROM user_ignore_vote_weights w
                           WHERE w.user_id = u.id
                             AND (w.valid_until IS NULL OR w.valid_until > CURRENT_TIMESTAMP)
                       ), 1) AS vote_weight
                FROM users u
                WHERE u.id = ? AND u.active = TRUE
                  AND u.role IN ('USER', 'ADMIN')
                  AND (u.valid_from IS NULL OR u.valid_from <= CURRENT_TIMESTAMP)
                  AND (u.valid_until IS NULL OR u.valid_until > CURRENT_TIMESTAMP)
                """, (rs, row) -> new Actor(UserRole.valueOf(rs.getString("role")), rs.getInt("vote_weight")), userId)
                .stream().findFirst().orElseThrow(ApiProblemException::authenticationRequired);
    }

    private String payload(UUID caseId, String status, int score, long version) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("status", status);
        payload.put("ignoreScore", score);
        payload.put("version", version);
        return payload.toString();
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > maxLength) throw ApiProblemException.validationFailed("Ignore reason is too long.");
        return normalized;
    }

    private record Actor(UserRole role, int weight) {}
    private record Snapshot(String status, long version) {}
}
