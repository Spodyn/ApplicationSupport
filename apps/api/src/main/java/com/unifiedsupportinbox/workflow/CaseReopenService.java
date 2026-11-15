package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.cases.ProviderCaseContextLock;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.sla.CaseSlaReopenRecorder;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
class CaseReopenService {
    private final JdbcTemplate jdbc;
    private final IdempotentCommandExecutor idempotency;
    private final ProviderCaseContextLock contextLock;
    private final CaseSlaReopenRecorder sla;
    private final AuditEventStore audit;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    CaseReopenService(JdbcTemplate jdbc, IdempotentCommandExecutor idempotency, ProviderCaseContextLock contextLock,
            CaseSlaReopenRecorder sla, AuditEventStore audit, OutboxEventStore outbox, ObjectMapper json) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.contextLock = contextLock;
        this.sla = sla;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
    }

    IdempotencyResult reopen(UUID caseId, UUID actorId, String key, String correlationId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND role = 'ADMIN' AND active
                    AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
                    AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP))
                """, Boolean.class, actorId))) throw ApiProblemException.accessDenied();
        return idempotency.execute(actorId, "case.admin.reopen:" + caseId, key, Map.of("caseId", caseId),
                () -> reopenInTransaction(caseId, actorId, correlationId));
    }

    private IdempotencyResponse reopenInTransaction(UUID caseId, UUID actorId, String correlationId) {
        var context = jdbc.query("""
                SELECT integration_id, channel_id, provider, external_conversation_id, external_thread_key
                FROM cases WHERE id = ?
                """, (rs, row) -> new Context(rs.getObject("integration_id", UUID.class),
                rs.getObject("channel_id", UUID.class), IntegrationProvider.valueOf(rs.getString("provider")),
                rs.getString("external_conversation_id"), rs.getString("external_thread_key")), caseId)
                .stream().findFirst().orElseThrow(() -> ApiProblemException.notFound("Case was not found."));
        contextLock.acquire(context.integration(), context.channel(), context.provider(), context.conversation(), context.thread());
        var before = jdbc.query("""
                SELECT status, resolved_at, owner_user_id, resolution_category, version
                FROM cases WHERE id = ? FOR UPDATE
                """, (rs, row) -> new Closed(rs.getString("status"), rs.getObject("resolved_at", OffsetDateTime.class),
                rs.getObject("owner_user_id", UUID.class), rs.getString("resolution_category"), rs.getLong("version")), caseId)
                .stream().findFirst().orElseThrow(() -> ApiProblemException.notFound("Case was not found."));
        if (!"RESOLVED".equals(before.status())) throw ApiProblemException.conflict("Only a resolved Case can be reopened.");

        UUID active = jdbc.query("""
                SELECT id FROM cases
                WHERE integration_id = ? AND channel_id = ? AND provider = ? AND external_conversation_id = ?
                  AND COALESCE(external_thread_key, '') = COALESCE(?, '')
                  AND status NOT IN ('IGNORED', 'RESOLVED')
                FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), context.integration(), context.channel(),
                context.provider().name(), context.conversation(), context.thread()).stream().findFirst().orElse(null);
        if (active != null) {
            audit.append(AuditActorType.ADMIN, actorId, "CASE_REOPEN_REDIRECTED", "CASE", caseId, caseId,
                    Map.of("activeCaseId", active.toString()));
            return result(active, false);
        }

        OffsetDateTime reopenedAt = jdbc.queryForObject("""
                UPDATE cases
                SET status = 'NEW', owner_user_id = NULL, resolved_at = NULL, resolution_category = NULL,
                    updated_at = statement_timestamp(), last_activity_at = statement_timestamp(), version = version + 1
                WHERE id = ? RETURNING updated_at
                """, OffsetDateTime.class, caseId);
        var metadata = new LinkedHashMap<>(sla.restartUnclaimed(caseId, reopenedAt.toInstant()));
        metadata.put("previousStatus", "RESOLVED");
        metadata.put("status", "NEW");
        metadata.put("previousResolvedAt", before.resolvedAt().toInstant().toString());
        metadata.put("version", before.version() + 1);
        if (before.owner() != null) metadata.put("previousOwnerUserId", before.owner().toString());
        if (before.category() != null) metadata.put("previousResolutionCategory", before.category());
        audit.append(AuditActorType.ADMIN, actorId, "CASE_REOPEN", "CASE", caseId, caseId, metadata);
        var event = json.createObjectNode().put("caseId", caseId.toString()).put("status", "NEW")
                .putNull("ownerUserId").put("version", before.version() + 1);
        outbox.append("case.updated", "case", caseId, event.toString(), correlationId);
        return result(caseId, true);
    }

    private IdempotencyResponse result(UUID target, boolean changed) {
        return new IdempotencyResponse(200, json.createObjectNode().put("caseId", target.toString()).put("changed", changed));
    }

    private record Context(UUID integration, UUID channel, IntegrationProvider provider, String conversation, String thread) {}
    private record Closed(String status, OffsetDateTime resolvedAt, UUID owner, String category, long version) {}
}
