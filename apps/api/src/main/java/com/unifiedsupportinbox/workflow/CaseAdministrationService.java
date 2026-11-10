package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.cases.CaseResolutionCategory;
import com.unifiedsupportinbox.sla.CaseSlaClaimRecorder;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Permission-gated administrative ownership and terminal Case commands. */
@Service
class CaseAdministrationService {

    enum Action { ASSIGN, REASSIGN, UNASSIGN, FORCE_RESOLVE }

    private final JdbcTemplate jdbc;
    private final IdempotentCommandExecutor idempotency;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;
    private final CaseSlaClaimRecorder sla;

    CaseAdministrationService(
            JdbcTemplate jdbc,
            IdempotentCommandExecutor idempotency,
            AuditEventStore audit,
            OutboxEventStore outbox,
            ObjectMapper json,
            CaseSlaClaimRecorder sla) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
        this.sla = sla;
    }

    List<Assignee> assignees(UUID caseId, UUID actorId) {
        requireAdmin(actorId);
        requireCase(caseId);
        return jdbc.query("""
                SELECT u.id, u.display_name,
                       NOT EXISTS (
                           SELECT 1 FROM case_ignore_votes v
                           WHERE v.case_id = ? AND v.user_id = u.id
                       ) AS eligible,
                       COALESCE(w.weight, 1) AS ignore_weight,
                       w.valid_until
                FROM users u
                LEFT JOIN user_ignore_vote_weights w
                  ON w.user_id = u.id
                 AND (w.valid_until IS NULL OR w.valid_until > CURRENT_TIMESTAMP)
                WHERE u.active = TRUE
                  AND u.role IN ('USER', 'ADMIN')
                  AND (u.valid_from IS NULL OR u.valid_from <= CURRENT_TIMESTAMP)
                  AND (u.valid_until IS NULL OR u.valid_until > CURRENT_TIMESTAMP)
                ORDER BY u.display_name, u.id
                """, (rs, row) -> {
            OffsetDateTime validUntil = rs.getObject("valid_until", OffsetDateTime.class);
            return new Assignee(
                    rs.getObject("id", UUID.class),
                    rs.getString("display_name"),
                    rs.getBoolean("eligible"),
                    rs.getInt("ignore_weight"),
                    validUntil == null ? null : validUntil.toInstant());
        }, caseId);
    }

    IdempotencyResult changeOwner(
            UUID caseId,
            UUID actorId,
            UUID targetUserId,
            Action action,
            String idempotencyKey,
            String correlationId) {
        return idempotency.execute(
                actorId,
                "case.admin." + action.name().toLowerCase(java.util.Locale.ROOT) + ":" + caseId,
                idempotencyKey,
                new CanonicalRequest(caseId, targetUserId, action.name(), null),
                () -> executeOwnerChange(caseId, actorId, targetUserId, action, correlationId));
    }

    IdempotencyResult forceResolve(
            UUID caseId,
            UUID actorId,
            CaseResolutionCategory category,
            String idempotencyKey,
            String correlationId) {
        return idempotency.execute(
                actorId,
                "case.admin.force-resolve:" + caseId,
                idempotencyKey,
                new CanonicalRequest(caseId, null, Action.FORCE_RESOLVE.name(), category == null ? null : category.name()),
                () -> executeForceResolve(caseId, actorId, category, correlationId));
    }

    /** Composed commands must share their caller's atomic transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    IdempotencyResponse executeOwnerChange(
            UUID caseId,
            UUID actorId,
            UUID targetUserId,
            Action action,
            String correlationId) {
        requireAdmin(actorId);
        Snapshot before = snapshot(caseId);
        boolean unclaimed = "NEW".equals(before.status()) || "PARTIALLY_IGNORED".equals(before.status());
        if (action == Action.ASSIGN && !unclaimed
                || action == Action.REASSIGN && !"VERIFICATION".equals(before.status())
                || action == Action.UNASSIGN && !"VERIFICATION".equals(before.status())) {
            throw ApiProblemException.conflict("The administrative ownership action is not available for this Case.");
        }
        if (action != Action.UNASSIGN) requireEligibleTarget(caseId, targetUserId);

        String status = action == Action.UNASSIGN ? "NEW" : "VERIFICATION";
        UUID owner = action == Action.UNASSIGN ? null : targetUserId;
        Long version = jdbc.queryForObject("""
                UPDATE cases
                SET status = ?,
                    owner_user_id = ?,
                    claimed_at = CASE
                        WHEN ?::uuid IS NOT NULL AND owner_user_id IS NULL THEN statement_timestamp()
                        ELSE claimed_at
                    END,
                    waiting_until = NULL,
                    updated_at = statement_timestamp(),
                    last_activity_at = statement_timestamp(),
                    version = version + 1
                WHERE id = ? AND version = ?
                RETURNING version
                """, Long.class, status, owner, owner, caseId, before.version());
        if (version == null) throw ApiProblemException.conflict("The Case changed before the ownership update.");

        if (action != Action.UNASSIGN) {
            jdbc.update("""
                    UPDATE case_ignore_votes
                    SET active = FALSE, deactivated_at = statement_timestamp()
                    WHERE case_id = ? AND active
                    """, caseId);
            jdbc.update("DELETE FROM case_snoozes WHERE case_id = ?", caseId);
            if (unclaimed) sla.recordClaim(caseId, java.time.Instant.now());
        } else {
            jdbc.update("""
                    UPDATE case_sla
                    SET unclaimed_completed_at = NULL,
                        unclaimed_outcome = NULL,
                        state = CASE
                            WHEN CURRENT_TIMESTAMP >= unclaimed_breach_at THEN 'BREACHED'
                            WHEN CURRENT_TIMESTAMP >= unclaimed_warning_at THEN 'WARNING'
                            ELSE 'ON_TRACK'
                        END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE case_id = ?
                    """, caseId);
        }
        record(actorId, caseId, action, before, status, owner, null, version, correlationId);
        return response(caseId, status, owner, null, version);
    }

    private IdempotencyResponse executeForceResolve(
            UUID caseId,
            UUID actorId,
            CaseResolutionCategory category,
            String correlationId) {
        requireAdmin(actorId);
        Snapshot before = snapshot(caseId);
        if ("IGNORED".equals(before.status()) || "RESOLVED".equals(before.status())) {
            throw ApiProblemException.conflict("The Case is already terminal.");
        }
        Long version = jdbc.queryForObject("""
                UPDATE cases
                SET status = 'RESOLVED',
                    resolved_at = statement_timestamp(),
                    resolution_category = ?,
                    waiting_until = NULL,
                    updated_at = statement_timestamp(),
                    last_activity_at = statement_timestamp(),
                    version = version + 1
                WHERE id = ? AND version = ?
                RETURNING version
                """, Long.class, category == null ? null : category.name(), caseId, before.version());
        if (version == null) throw ApiProblemException.conflict("The Case changed before it could be force-resolved.");
        jdbc.update("DELETE FROM case_snoozes WHERE case_id = ?", caseId);
        record(actorId, caseId, Action.FORCE_RESOLVE, before, "RESOLVED", before.ownerId(), category, version, correlationId);
        return response(caseId, "RESOLVED", before.ownerId(), category, version);
    }

    private void record(
            UUID actorId,
            UUID caseId,
            Action action,
            Snapshot before,
            String status,
            UUID owner,
            CaseResolutionCategory category,
            long version,
            String correlationId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", action.name());
        metadata.put("previousStatus", before.status());
        metadata.put("status", status);
        metadata.put("version", version);
        if (before.ownerId() != null) metadata.put("previousOwnerUserId", before.ownerId().toString());
        if (owner != null) metadata.put("ownerUserId", owner.toString());
        if (category != null) metadata.put("resolutionCategory", category.name());
        audit.append(AuditActorType.ADMIN, actorId, "CASE_" + action.name(), "CASE", caseId, caseId, Map.copyOf(metadata));
        outbox.append("case.updated", "case", caseId, payload(caseId, status, owner, category, version), correlationId);
    }

    private void requireAdmin(UUID actorId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users
                WHERE id = ? AND role = 'ADMIN' AND active = TRUE
                  AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
                  AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, Integer.class, actorId);
        if (count == null || count != 1) throw ApiProblemException.accessDenied();
    }

    private void requireEligibleTarget(UUID caseId, UUID targetUserId) {
        if (targetUserId == null) throw ApiProblemException.validationFailed("targetUserId is required.");
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users u
                WHERE u.id = ? AND u.active = TRUE
                  AND u.role IN ('USER', 'ADMIN')
                  AND (u.valid_from IS NULL OR u.valid_from <= CURRENT_TIMESTAMP)
                  AND (u.valid_until IS NULL OR u.valid_until > CURRENT_TIMESTAMP)
                  AND NOT EXISTS (
                      SELECT 1 FROM case_ignore_votes v
                      WHERE v.case_id = ? AND v.user_id = u.id
                  )
                """, Integer.class, targetUserId, caseId);
        if (count == null || count != 1) {
            throw ApiProblemException.conflict("Assignment target is not eligible for this Case.");
        }
    }

    private void requireCase(UUID caseId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM cases WHERE id = ?", Integer.class, caseId);
        if (count == null || count != 1) throw ApiProblemException.notFound("Case was not found.");
    }

    private Snapshot snapshot(UUID caseId) {
        return jdbc.query("SELECT status, owner_user_id, version FROM cases WHERE id = ? FOR UPDATE",
                (rs, row) -> new Snapshot(rs.getString("status"), rs.getObject("owner_user_id", UUID.class), rs.getLong("version")),
                caseId).stream().findFirst().orElseThrow(() -> ApiProblemException.notFound("Case was not found."));
    }

    private IdempotencyResponse response(
            UUID caseId, String status, UUID owner, CaseResolutionCategory category, long version) {
        ObjectNode response = json.createObjectNode();
        response.put("caseId", caseId.toString());
        response.put("status", status);
        if (owner == null) response.putNull("ownerUserId"); else response.put("ownerUserId", owner.toString());
        if (category == null) response.putNull("resolutionCategory"); else response.put("resolutionCategory", category.name());
        response.put("version", version);
        return new IdempotencyResponse(200, response);
    }

    private String payload(UUID caseId, String status, UUID owner, CaseResolutionCategory category, long version) {
        return response(caseId, status, owner, category, version).body().toString();
    }

    record Assignee(UUID id, String displayName, boolean eligible, int ignoreWeight, java.time.Instant ignoreWeightValidUntil) {}
    private record Snapshot(String status, UUID ownerId, long version) {}
    private record CanonicalRequest(UUID caseId, UUID targetUserId, String action, String resolutionCategory) {}
}
