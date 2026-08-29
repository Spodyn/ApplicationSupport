package com.unifiedsupportinbox.provider.slack.internal;

import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/providers/slack")
class SlackWebhookController {

    private final SlackWebhookService webhooks;

    SlackWebhookController(SlackWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> events(
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {
        Optional<String> challenge = webhooks.handle(timestamp, signature, rawBody);
        if (challenge.isPresent()) {
            return ResponseEntity.ok(Map.of("challenge", challenge.get()));
        }
        return ResponseEntity.ok().build();
    }
}
