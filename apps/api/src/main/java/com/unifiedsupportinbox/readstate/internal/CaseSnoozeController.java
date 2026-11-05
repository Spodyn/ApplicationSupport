package com.unifiedsupportinbox.readstate.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.IdempotencyResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/cases")
class CaseSnoozeController {

    private static final String CORRELATION_ID_ATTRIBUTE = "usi.correlationId";

    private final CaseSnoozeService snoozes;

    CaseSnoozeController(CaseSnoozeService snoozes) {
        this.snoozes = snoozes;
    }

    @PostMapping("/{caseId}/snooze")
    ResponseEntity<JsonNode> snooze(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody SnoozeRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        IdempotencyResult result = snoozes.snooze(
                caseId,
                authenticatedUserId(authentication),
                body.until(),
                idempotencyKey,
                correlationId(request));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @DeleteMapping("/{caseId}/snooze")
    ResponseEntity<JsonNode> cancel(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        IdempotencyResult result = snoozes.cancel(
                caseId,
                authenticatedUserId(authentication),
                idempotencyKey,
                correlationId(request));
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

    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        return value instanceof String id ? id : UUID.randomUUID().toString();
    }

    record SnoozeRequest(@NotNull Instant until) {}
}
