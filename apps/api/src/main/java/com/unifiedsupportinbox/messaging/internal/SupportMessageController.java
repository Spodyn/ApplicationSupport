package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/cases")
class SupportMessageController {

    private static final String CORRELATION_ID_ATTRIBUTE = "usi.correlationId";

    private final SupportSendMessageService service;
    private final MessageHistoryService history;
    private final CaseInternalNoteService notes;

    SupportMessageController(SupportSendMessageService service, MessageHistoryService history, CaseInternalNoteService notes) {
        this.service = service;
        this.history = history;
        this.notes = notes;
    }

    @GetMapping("/{caseId}/messages")
    CursorPage<MessageHistoryService.MessageHistoryItem> history(
            @PathVariable UUID caseId,
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = ApiV1Conventions.LIMIT_QUERY_PARAMETER, required = false) @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) Integer limit,
            @RequestParam(name = "includeInternalNotes", defaultValue = "false") boolean includeInternalNotes,
            Authentication authentication) {
        return history.history(caseId, before, limit, authenticatedUserId(authentication), includeInternalNotes);
    }

    @PostMapping("/{caseId}/internal-notes")
    ResponseEntity<JsonNode> addInternalNote(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestBody InternalNoteRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        IdempotencyResult result = notes.add(caseId, authenticatedUserId(authentication), idempotencyKey,
                request.body(), correlationId(httpRequest));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/{caseId}/messages")
    ResponseEntity<JsonNode> send(
            @PathVariable UUID caseId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestBody SendMessageRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        UUID userId = authenticatedUserId(authentication);
        IdempotencyResult result = service.send(
                caseId,
                userId,
                idempotencyKey,
                request.body(),
                request.bodyFormat(),
                request.attachmentIds(),
                correlationId(httpRequest));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        return value instanceof String correlationId ? correlationId : UUID.randomUUID().toString();
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

    record InternalNoteRequest(String body) {}

    record SendMessageRequest(
            String body,
            MessageBodyFormat bodyFormat,
            List<UUID> attachmentIds) {
    }
}
