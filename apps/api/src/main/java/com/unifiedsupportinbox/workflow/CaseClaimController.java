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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/cases")
class CaseClaimController {
    private static final String CORRELATION_ID_ATTRIBUTE = "usi.correlationId";
    private final CaseClaimService claims;

    CaseClaimController(CaseClaimService claims) { this.claims = claims; }

    @PostMapping("/{caseId}/claim")
    ResponseEntity<JsonNode> claim(@PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            Authentication authentication, HttpServletRequest request) {
        UUID userId = authenticatedUserId(authentication);
        Object value = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        String correlationId = value instanceof String id ? id : UUID.randomUUID().toString();
        IdempotencyResult result = claims.claim(caseId, userId, idempotencyKey, correlationId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    private static UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw ApiProblemException.authenticationRequired();
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException exception) { throw ApiProblemException.authenticationRequired(); }
    }
}
