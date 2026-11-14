package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorPage;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/direct-messages")
class DirectMessageController {
    private final DirectMessageService directMessages;

    DirectMessageController(DirectMessageService directMessages) {
        this.directMessages = directMessages;
    }

    @GetMapping("/recipients")
    CursorPage<DirectMessageService.Recipient> recipients(Authentication auth,
            @RequestParam(required = false) String search, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return directMessages.recipients(actor(auth), search, cursor, limit);
    }

    @GetMapping("/conversations")
    CursorPage<DirectMessageService.Conversation> conversations(Authentication auth,
            @RequestParam(required = false) String search, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return directMessages.conversations(actor(auth), search, cursor, limit);
    }

    @GetMapping("/conversations/{id}")
    DirectMessageService.Conversation conversation(Authentication auth, @PathVariable UUID id) {
        return directMessages.conversation(actor(auth), id);
    }

    @PostMapping("/conversations")
    DirectMessageService.Conversation start(Authentication auth, @RequestBody StartRequest request) {
        return directMessages.start(actor(auth), request.recipientId());
    }

    @GetMapping("/conversations/{id}/messages")
    CursorPage<DirectMessageService.Message> messages(Authentication auth, @PathVariable UUID id,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return directMessages.messages(actor(auth), id, cursor, limit);
    }

    @PostMapping("/conversations/{id}/messages")
    ResponseEntity<JsonNode> send(Authentication auth, @PathVariable UUID id,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String key, @RequestBody SendRequest request) {
        var result = directMessages.send(actor(auth), id, key, request.body());
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PutMapping("/conversations/{id}/read-position")
    ResponseEntity<Void> read(Authentication auth, @PathVariable UUID id, @RequestBody ReadRequest request) {
        directMessages.read(actor(auth), id, request.messageId());
        return ResponseEntity.noContent().build();
    }

    private static UUID actor(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) throw ApiProblemException.authenticationRequired();
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException invalid) {
            throw ApiProblemException.authenticationRequired();
        }
    }

    record StartRequest(UUID recipientId) { }
    record SendRequest(String body) { }
    record ReadRequest(UUID messageId) { }
}
