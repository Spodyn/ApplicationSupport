package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/messages/{messageId}/mutations")
class MessageMutationController {
    private final MessageMutationService mutations;
    MessageMutationController(MessageMutationService mutations){this.mutations=mutations;}
    @PostMapping
    ResponseEntity<JsonNode> request(@PathVariable UUID messageId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String key,
            @RequestBody Input input,Authentication authentication) {
        if(input==null||input.expectedRevision()==null)throw ApiProblemException.validationFailed("Message revision is required.");
        var result=mutations.request(messageId,actor(authentication),key,input.action(),input.body(),input.expectedRevision(),UUID.randomUUID().toString());
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @PostMapping("/{operationId}/retry")
    ResponseEntity<JsonNode> retry(@PathVariable UUID messageId,@PathVariable UUID operationId,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String key,Authentication authentication) {
        var result=mutations.retry(messageId,operationId,actor(authentication),key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
    private static UUID actor(Authentication authentication) {
        if(authentication==null||!authentication.isAuthenticated())throw ApiProblemException.authenticationRequired();
        try{return UUID.fromString(authentication.getName());}catch(IllegalArgumentException bad){throw ApiProblemException.authenticationRequired();}
    }
    record Input(String action,String body,Long expectedRevision) {}
}
