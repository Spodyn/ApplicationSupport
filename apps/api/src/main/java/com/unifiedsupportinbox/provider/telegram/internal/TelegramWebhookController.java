package com.unifiedsupportinbox.provider.telegram.internal;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/provider-callbacks/telegram")
class TelegramWebhookController {
    private final TelegramWebhookService webhooks;

    TelegramWebhookController(TelegramWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken,
            @RequestBody byte[] rawBody) {
        webhooks.handle(secretToken, rawBody);
        return ResponseEntity.ok().build();
    }
}
