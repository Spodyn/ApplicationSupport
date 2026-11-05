package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.IdempotencyResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/admin/cases")
class CaseAdministrationController {
    private final CaseAdministrationService cases;

    CaseAdministrationController(CaseAdministrationService cases) {
        this.cases = cases;
    }

    @GetMapping("/{caseId}/assignees")
    List<CaseAdministrationService.Assignee> assignees(
            @PathVariable UUID caseId, Authentication authentication) {
        return cases.assignees(caseId, authenticatedUserId(authentication));
    }

    @PostMapping("/{caseId}/assign")
    ResponseEntity<JsonNode> assign(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String key,
            @RequestBody AssignmentRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return result(cases.changeOwner(caseId, authenticatedUserId(authentication), body.targetUserId(),
                CaseAdministrationService.Action.ASSIGN, key, correlationId(request)));
    }

    @PostMapping("/{caseId}/reassign")
    ResponseEntity<JsonNode> reassign(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String key,
            @RequestBody AssignmentRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return result(cases.changeOwner(caseId, authenticatedUserId(authentication), body.targetUserId(),
                CaseAdministrationService.Action.REASSIGN, key, correlationId(request)));
    }

    @PostMapping("/{caseId}/unassign")
    ResponseEntity<JsonNode> unassign(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication,
            HttpServletRequest request) {
        return result(cases.changeOwner(caseId, authenticatedUserId(authentication), null,
                CaseAdministrationService.Action.UNASSIGN, key, correlationId(request)));
    }

    @PostMapping("/{caseId}/force-resolve")
    ResponseEntity<JsonNode> forceResolve(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String key,
            @RequestBody(required = false) ResolveRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return result(cases.forceResolve(caseId, authenticatedUserId(authentication),
                CaseResolveController.resolutionCategory(body == null ? null : body.resolutionCategory()),
                key, correlationId(request)));
    }

    private static ResponseEntity<JsonNode> result(IdempotencyResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }

    private static UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw ApiProblemException.authenticationRequired();
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.authenticationRequired();
        }
    }

    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute("usi.correlationId");
        return value instanceof String id ? id : UUID.randomUUID().toString();
    }

    record AssignmentRequest(UUID targetUserId) {}
    record ResolveRequest(String resolutionCategory) {}
}
