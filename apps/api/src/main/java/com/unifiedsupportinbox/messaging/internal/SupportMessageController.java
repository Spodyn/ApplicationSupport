package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorPage;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/cases")
class SupportMessageController {

    private static final String CORRELATION_ID_ATTRIBUTE = "usi.correlationId";

    private final SupportSendMessageService service;
    private final MessageHistoryService history;

    SupportMessageController(SupportSendMessageService service, MessageHistoryService history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping("/{caseId}/messages")
    CursorPage<MessageHistoryService.MessageHistoryItem> history(
            @PathVariable UUID caseId,
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = ApiV1Conventions.LIMIT_QUERY_PARAMETER, required = false) Integer limit,
            Authentication authentication) {
        authenticatedUserId(authentication);
        return history.history(caseId, before, limit);
    }

    @PostMapping("/{caseId}/messages")
    ResponseEntity<JsonNode> send(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody SendMessageRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        UUID userId = authenticatedUserId(authentication);
        Object correlationValue = httpRequest.getAttribute(CORRELATION_ID_ATTRIBUTE);
        String correlationId = correlationValue instanceof String value ? value : UUID.randomUUID().toString();

        IdempotencyResult result = service.send(
                caseId,
                userId,
                idempotencyKey,
                request.body(),
                request.bodyFormat(),
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

    record SendMessageRequest(
            @NotBlank String body,
            MessageBodyFormat bodyFormat) {
    }
}
