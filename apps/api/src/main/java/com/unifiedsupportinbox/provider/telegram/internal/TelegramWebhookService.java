package com.unifiedsupportinbox.provider.telegram.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class TelegramWebhookService {
    private final TelegramWebhookAuthenticator authenticator;
    private final TelegramInboundDeliveryService inboundEvents;
    private final ObjectMapper objectMapper;
    TelegramWebhookService(
            TelegramWebhookAuthenticator authenticator,
            TelegramInboundDeliveryService inboundEvents,
            ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.inboundEvents = inboundEvents;
        this.objectMapper = objectMapper;
    }
    void handle(String secretToken, byte[] rawBody) {
        CredentialReference integration = authenticator.authenticate(secretToken);
        JsonNode payload = parse(rawBody);
        JsonNode updateId = payload.get("update_id");
        if (updateId == null || !updateId.isIntegralNumber() || !updateId.canConvertToLong()
                || updateId.asLong() < 0) {
            throw ApiProblemException.validationFailed("Telegram update_id is required.");
        }
        inboundEvents.persistAndWake(
                integration.integrationId(),
                Long.toString(updateId.asLong()),
                new String(rawBody, StandardCharsets.UTF_8),
                UUID.randomUUID().toString());
    }
    private JsonNode parse(byte[] rawBody) {
        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            if (payload == null || !payload.isObject()) {
                throw ApiProblemException.validationFailed("Telegram request body must be a JSON object.");
            }
            return payload;
        } catch (JacksonException exception) {
            throw ApiProblemException.validationFailed("Telegram request body must be valid JSON.");
        }
    }
}
