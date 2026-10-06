package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.cases.CaseResolutionCategory;
import com.unifiedsupportinbox.identity.UserRole;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Owner-only idempotent VERIFICATION -> RESOLVED command. */
@Service
public class CaseResolveService {

    private static final String COMMAND_SCOPE = "case.resolve";
    private static final String OUTBOX_TYPE = "case.updated";

    private final JdbcTemplate jdbc;
    private final IdempotentCommandExecutor idempotency;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    public CaseResolveService(
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

    public IdempotencyResult resolve(
            UUID caseId,
            UUID userId,
            String idempotencyKey,
            CaseResolutionCategory category,
            String correlationId) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(userId, "userId");
        requireCorrelationId(correlationId);
        return idempotency.execute(
                userId,
                COMMAND_SCOPE + ":" + caseId,
                idempotencyKey,
                new CanonicalResolveRequest(category == null ? null : category.name()),
                () -> executeResolve(caseId, userId, category, correlationId));
    }

    private IdempotencyResponse executeResolve(
            UUID caseId,
            UUID userId,
            CaseResolutionCategory category,
            String correlationId) {
        Actor actor = loadActor(userId);
        if (!actor.active()) throw ApiProblemException.accessDenied();

        CaseSnapshot snapshot = jdbc.query("""
                SELECT status, owner_user_id, version
                FROM cases
                WHERE id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new CaseSnapshot(
                    rs.getString("status"),
                    rs.getObject("owner_user_id", UUID.class),
                    rs.getLong("version")),
                caseId).stream().findFirst()
                .orElseThrow(() -> ApiProblemException.notFound("Case was not found."));

        if (!"VERIFICATION".equals(snapshot.status())) {
            throw ApiProblemException.conflict("The Case cannot be resolved in its current state.");
        }
        if (!userId.equals(snapshot.ownerUserId())) {
            throw ApiProblemException.accessDenied();
        }

        Resolved resolved = jdbc.query("""
                UPDATE cases
                SET status = 'RESOLVED',
                    resolved_at = statement_timestamp(),
                    resolution_category = ?,
                    waiting_until = NULL,
                    updated_at = statement_timestamp(),
                    last_activity_at = statement_timestamp(),
                    version = version + 1
                WHERE id = ? AND version = ? AND status = 'VERIFICATION' AND owner_user_id = ?
                RETURNING version, resolved_at
                """, (rs, rowNum) -> new Resolved(
                    rs.getLong("version"),
                    rs.getObject("resolved_at", OffsetDateTime.class)),
                category == null ? null : category.name(),
                caseId,
                snapshot.version(),
                userId).stream().findFirst()
                .orElseThrow(() -> ApiProblemException.conflict("The Case changed before it could be resolved."));

        // Terminal Cases have no active personal reminder. SLA schedulers use Case terminal
        // state as the authoritative stop condition; historical SLA deadlines stay intact.
        jdbc.update("DELETE FROM case_snoozes WHERE case_id = ?", caseId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", "RESOLVE");
        metadata.put("previousStatus", snapshot.status());
        metadata.put("status", "RESOLVED");
        metadata.put("ownerUserId", userId.toString());
        metadata.put("version", resolved.version());
        if (category != null) metadata.put("resolutionCategory", category.name());
        audit.append(
                actor.role() == UserRole.ADMIN ? AuditActorType.ADMIN : AuditActorType.USER,
                userId,
                "CASE_RESOLVE",
                "CASE",
                caseId,
                caseId,
                Map.copyOf(metadata));

        outbox.append(
                OUTBOX_TYPE,
                "case",
                caseId,
                payload(caseId, userId, category, resolved.version()),
                correlationId);

        ObjectNode response = json.createObjectNode();
        response.put("caseId", caseId.toString());
        response.put("status", "RESOLVED");
        response.put("ownerUserId", userId.toString());
        if (category == null) response.putNull("resolutionCategory");
        else response.put("resolutionCategory", category.name());
        response.put("version", resolved.version());
        response.put("resolvedAt", resolved.resolvedAt().toInstant().toString());
        return new IdempotencyResponse(200, response);
    }

    private Actor loadActor(UUID userId) {
        return jdbc.query("""
                SELECT active, role
                FROM users
                WHERE id = ?
                """, (rs, rowNum) -> new Actor(
                    rs.getBoolean("active"),
                    UserRole.valueOf(rs.getString("role"))),
                userId).stream().findFirst()
                .orElseThrow(ApiProblemException::authenticationRequired);
    }

    private String payload(
            UUID caseId,
            UUID ownerUserId,
            CaseResolutionCategory category,
            long version) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("status", "RESOLVED");
        payload.put("ownerUserId", ownerUserId.toString());
        payload.put("version", version);
        if (category == null) payload.putNull("resolutionCategory");
        else payload.put("resolutionCategory", category.name());
        return payload.toString();
    }

    private static void requireCorrelationId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("correlationId must contain 1 to 128 characters");
        }
    }

    private record Actor(boolean active, UserRole role) {}
    private record CaseSnapshot(String status, UUID ownerUserId, long version) {}
    private record Resolved(long version, OffsetDateTime resolvedAt) {}
    private record CanonicalResolveRequest(String resolutionCategory) {}
}
