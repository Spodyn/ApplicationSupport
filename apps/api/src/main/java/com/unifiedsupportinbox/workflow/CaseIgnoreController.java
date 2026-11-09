package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.IdempotencyResult;
import jakarta.servlet.http.HttpServletRequest;
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
class CaseIgnoreController {
    private final CaseIgnoreService ignores;

    CaseIgnoreController(CaseIgnoreService ignores) {
        this.ignores = ignores;
    }

    @PostMapping("/{caseId}/ignore")
    ResponseEntity<JsonNode> ignore(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestBody(required = false) IgnoreRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        IdempotencyResult result = ignores.ignore(
                caseId,
                authenticatedUserId(authentication),
                body == null ? null : body.reason(),
                idempotencyKey,
                correlationId(request));
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

    record IgnoreRequest(String reason) {}
}
