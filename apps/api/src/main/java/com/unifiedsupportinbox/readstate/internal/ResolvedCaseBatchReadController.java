package com.unifiedsupportinbox.readstate.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/cases/read")
class ResolvedCaseBatchReadController {

    private final ResolvedCaseBatchReadService service;

    ResolvedCaseBatchReadController(ResolvedCaseBatchReadService service) {
        this.service = service;
    }

    @PostMapping("/mark-resolved")
    ResponseEntity<JsonNode> markResolvedRead(
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestParam(name = "provider", required = false) IntegrationProvider provider,
            Authentication authentication) {
        IdempotencyResult result = service.markResolvedRead(
                authenticatedUserId(authentication),
                idempotencyKey, provider);
        return ResponseEntity.status(result.status()).body(result.body());
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
}
