package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/** Bounded, sequential orchestration; each item has its own durable transaction/replay. */
@Service
class CaseBulkService {
    static final int MAX_CASES = 100;
    enum Action { ASSIGN, RESOLVE, IGNORE, CLAIM }
    record Request(List<UUID> caseIds, Action action, UUID targetUserId) {}

    private final JdbcTemplate jdbc;
    private final IdempotentCommandExecutor commands;
    private final CaseDetailService details;
    private final CaseAdministrationService administration;
    private final CaseResolveService resolve;
    private final CaseIgnoreService ignore;
    private final CaseClaimService claim;
    private final ObjectMapper json;

    CaseBulkService(JdbcTemplate jdbc, IdempotentCommandExecutor commands, CaseDetailService details,
            CaseAdministrationService administration, CaseResolveService resolve, CaseIgnoreService ignore,
            CaseClaimService claim, ObjectMapper json) {
        this.jdbc = jdbc;
        this.commands = commands;
        this.details = details;
        this.administration = administration;
        this.resolve = resolve;
        this.ignore = ignore;
        this.claim = claim;
        this.json = json;
    }

    JsonNode execute(UUID actor, String key, Request request, String correlationId) {
        validate(request);
        requireActor(actor, request.action());
        // Register the entire immutable selection before any effects. Reusing the key
        // with another action/selection/target fails even when the sets are disjoint.
        commands.execute(actor, "case.bulk", key, request,
                () -> new IdempotencyResponse(200, json.createObjectNode().put("registered", true)));
        var results = json.createArrayNode();
        for (UUID caseId : request.caseIds()) {
            String scope = "case.bulk.item:" + caseId;
            JsonNode item;
            try {
                item = commands.execute(actor, scope, key, request,
                        () -> apply(actor, caseId, key, request, correlationId)).body();
            } catch (RuntimeException error) {
                // The failed item's transaction (including audit/outbox) has rolled
                // back. Persist a sanitized outcome in a fresh transaction. A DB
                // outage may interrupt the request; replay resumes committed items.
                boolean skipped = error instanceof ApiProblemException problem && problem.status().is4xxClientError();
                String code = error instanceof ApiProblemException problem ? problem.code().name() : "APPLICATION_FAILURE";
                item = commands.execute(actor, scope, key, request, () -> outcome(caseId,
                        skipped ? "SKIPPED" : "FAILED", code)).body();
            }
            results.add(item);
        }
        var response = json.createObjectNode();
        response.set("items", results);
        return response;
    }

    private IdempotencyResponse apply(UUID actor, UUID id, String key, Request request, String correlationId) {
        requireActor(actor, request.action());
        // Serialize eligibility and command against concurrent workflow changes.
        jdbc.queryForList("SELECT id FROM cases WHERE id = ? FOR UPDATE", id);
        var actions = details.detail(id, actor).availableActions();
        String action = request.action().name();
        if (request.action() == Action.ASSIGN && !actions.contains("ASSIGN") && actions.contains("REASSIGN")) {
            action = "REASSIGN";
        }
        if (!actions.contains(action)) return outcome(id, "SKIPPED", "ACTION_UNAVAILABLE");
        switch (request.action()) {
            case ASSIGN -> administration.changeOwner(id, actor, request.targetUserId(),
                    CaseAdministrationService.Action.valueOf(action), key, correlationId);
            case RESOLVE -> resolve.resolve(id, actor, key, null, correlationId);
            case IGNORE -> ignore.ignore(id, actor, null, key, correlationId);
            case CLAIM -> claim.claim(id, actor, key, correlationId);
        }
        return outcome(id, "SUCCEEDED", "OK");
    }

    private IdempotencyResponse outcome(UUID id, String status, String code) {
        return new IdempotencyResponse(200, json.createObjectNode()
                .put("caseId", id.toString()).put("status", status).put("code", code));
    }

    private void requireActor(UUID actor, Action action) {
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users u WHERE u.id = ? AND u.active
                  AND (u.valid_from IS NULL OR u.valid_from <= CURRENT_TIMESTAMP)
                  AND (u.valid_until IS NULL OR u.valid_until > CURRENT_TIMESTAMP)
                  AND (? <> 'ASSIGN' OR (u.role = 'ADMIN' AND EXISTS (
                    SELECT 1 FROM user_permissions p WHERE p.user_id = u.id AND p.permission_code = 'reassign_cases'))))
                """, Boolean.class, actor, action.name());
        if (!Boolean.TRUE.equals(allowed)) throw ApiProblemException.accessDenied();
    }

    static void validate(Request request) {
        if (request == null || request.action() == null || request.caseIds() == null
                || request.caseIds().isEmpty() || request.caseIds().size() > MAX_CASES
                || request.caseIds().stream().anyMatch(java.util.Objects::isNull)
                || request.caseIds().stream().distinct().count() != request.caseIds().size()
                || (request.action() == Action.ASSIGN) != (request.targetUserId() != null)) {
            throw ApiProblemException.validationFailed("Select 1 to 100 distinct Case IDs and a valid bulk action/target.");
        }
    }
}
