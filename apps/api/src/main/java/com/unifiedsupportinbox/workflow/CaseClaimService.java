package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.sla.CaseSlaClaimRecorder;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Dedicated idempotent command for the single-owner Case claim invariant. */
@Service
public class CaseClaimService {

    private static final String COMMAND_SCOPE = "case.claim";
    private static final String OUTBOX_TYPE = "case.claimed";

    private final JdbcTemplate jdbc;
    private final IdempotentCommandExecutor idempotency;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;
    private final CaseSlaClaimRecorder sla;

    public CaseClaimService(JdbcTemplate jdbc, IdempotentCommandExecutor idempotency,
                            AuditEventStore audit, OutboxEventStore outbox, ObjectMapper json,
                            CaseSlaClaimRecorder sla) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
        this.sla = sla;
    }

    public IdempotencyResult claim(UUID caseId, UUID userId, String idempotencyKey, String correlationId) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(userId, "userId");
        requireCorrelationId(correlationId);
        return idempotency.execute(userId, COMMAND_SCOPE + ":" + caseId, idempotencyKey,
                Map.of("caseId", caseId.toString()),
                () -> executeClaim(caseId, userId, correlationId));
    }

    private IdempotencyResponse executeClaim(UUID caseId, UUID userId, String correlationId) {
        Actor actor = loadActor(caseId, userId);
        if (!actor.active()) throw ApiProblemException.accessDenied();
        if (actor.lifetimeIgnoreVoter()) {
            throw ApiProblemException.conflict("A user who has voted Ignore cannot claim this Case.");
        }

        Claim claim = jdbc.query("""
                UPDATE cases
                SET owner_user_id = ?, status = 'VERIFICATION', claimed_at = statement_timestamp(),
                    updated_at = statement_timestamp(), last_activity_at = statement_timestamp(), version = version + 1
                WHERE id = ? AND owner_user_id IS NULL AND status IN ('NEW', 'PARTIALLY_IGNORED')
                RETURNING version, claimed_at
                """, (rs, rowNum) -> new Claim(rs.getLong("version"),
                        rs.getObject("claimed_at", OffsetDateTime.class).toInstant()), userId, caseId)
                .stream().findFirst().orElse(null);
        if (claim == null) {
            if (jdbc.queryForObject("SELECT count(*) FROM cases WHERE id = ?", Integer.class, caseId) == 0) {
                throw ApiProblemException.notFound("Case was not found.");
            }
            throw ApiProblemException.caseAlreadyClaimed();
        }

        jdbc.update("""
                UPDATE case_ignore_votes SET active = FALSE, deactivated_at = statement_timestamp()
                WHERE case_id = ? AND active
                """, caseId);
        jdbc.update("DELETE FROM case_snoozes WHERE case_id = ?", caseId);
        sla.recordClaim(caseId, claim.claimedAt());

        audit.append(actor.role() == UserRole.ADMIN ? AuditActorType.ADMIN : AuditActorType.USER,
                userId, "CASE_CLAIM", "CASE", caseId, caseId,
                Map.of("action", "CLAIM", "previousStatus", "UNOWNED", "status", "VERIFICATION",
                        "ownerUserId", userId.toString(), "version", claim.version()));
        outbox.append(OUTBOX_TYPE, "case", caseId, payload(caseId, userId, claim.version()), correlationId);

        ObjectNode response = json.createObjectNode();
        response.put("caseId", caseId.toString());
        response.put("status", "VERIFICATION");
        response.put("ownerUserId", userId.toString());
        response.put("version", claim.version());
        return new IdempotencyResponse(200, response);
    }

    private Actor loadActor(UUID caseId, UUID userId) {
        return jdbc.query("""
                SELECT u.active, u.role,
                       EXISTS (SELECT 1 FROM case_ignore_votes v WHERE v.case_id = ? AND v.user_id = ?) AS voted
                FROM users u WHERE u.id = ?
                """, (rs, rowNum) -> new Actor(rs.getBoolean("active"),
                UserRole.valueOf(rs.getString("role")), rs.getBoolean("voted")), caseId, userId, userId)
                .stream().findFirst().orElseThrow(ApiProblemException::authenticationRequired);
    }

    private String payload(UUID caseId, UUID userId, long version) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("ownerUserId", userId.toString());
        payload.put("status", "VERIFICATION");
        payload.put("version", version);
        return payload.toString();
    }

    private static void requireCorrelationId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("correlationId must contain 1 to 128 characters");
        }
    }

    private record Actor(boolean active, UserRole role, boolean lifetimeIgnoreVoter) {}
    private record Claim(long version, java.time.Instant claimedAt) {}
}
