package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.cases.CaseResolutionCategory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/cases")
class CaseResolveController {

    private static final String CORRELATION_ID_ATTRIBUTE = "usi.correlationId";
    private final CaseResolveService resolves;

    CaseResolveController(CaseResolveService resolves) {
        this.resolves = resolves;
    }

    @PostMapping("/{caseId}/resolve")
    ResponseEntity<JsonNode> resolve(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestBody ResolveRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        UUID userId = authenticatedUserId(authentication);
        Object value = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        String correlationId = value instanceof String id ? id : UUID.randomUUID().toString();
        IdempotencyResult result = resolves.resolve(
                caseId,
                userId,
                idempotencyKey,
                resolutionCategory(body.resolutionCategory()),
                correlationId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    private static CaseResolutionCategory resolutionCategory(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw ApiProblemException.validationFailed("resolutionCategory must be omitted or use a supported code.");
        }
        try {
            return CaseResolutionCategory.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed("Unsupported resolutionCategory.");
        }
    }

    private static UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiProblemException.authenticationRequired();
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.authenticationRequired();
        }
    }

    record ResolveRequest(String resolutionCategory) {}
}
