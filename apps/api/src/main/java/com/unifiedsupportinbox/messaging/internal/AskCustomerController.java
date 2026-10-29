package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
class AskCustomerController {

    private static final String CORRELATION_ID_ATTRIBUTE = "usi.correlationId";
    private final AskCustomerService asks;

    AskCustomerController(AskCustomerService asks) {
        this.asks = asks;
    }

    @PostMapping("/{caseId}/ask-customer")
    ResponseEntity<JsonNode> ask(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody AskCustomerRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        UUID userId = authenticatedUserId(authentication);
        Object correlationValue = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        String correlationId = correlationValue instanceof String value ? value : UUID.randomUUID().toString();

        IdempotencyResult result = asks.ask(
                caseId,
                userId,
                idempotencyKey,
                body.message(),
                body.bodyFormat(),
                body.waitingMinutes(),
                correlationId);
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

    record AskCustomerRequest(
            @NotBlank String message,
            MessageBodyFormat bodyFormat,
            Long waitingMinutes) {}
}
