package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.InboundEventStore;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class SlackWebhookService {

    private final SlackRequestAuthenticator authenticator;
    private final InboundEventStore inboundEvents;
    private final ObjectMapper objectMapper;

    SlackWebhookService(
            SlackRequestAuthenticator authenticator,
            InboundEventStore inboundEvents,
            ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.inboundEvents = inboundEvents;
        this.objectMapper = objectMapper;
    }

    Optional<String> handle(String timestamp, String signature, byte[] rawBody) {
        List<CredentialReference> verified = authenticator.authenticate(timestamp, signature, rawBody);
        JsonNode payload = parse(rawBody);
        CredentialReference integration = selectIntegration(verified, text(payload, "team_id"));
        String type = requiredText(payload, "type", "Slack request type is required.");

        if ("url_verification".equals(type)) {
            return Optional.of(requiredText(payload, "challenge", "Slack URL verification challenge is required."));
        }
        if (!"event_callback".equals(type)) {
            throw ApiProblemException.validationFailed("Unsupported Slack callback type.");
        }

        String eventId = requiredText(payload, "event_id", "Slack event_id is required.");
        String payloadJson = new String(rawBody, StandardCharsets.UTF_8);
        inboundEvents.persistAuthenticated(
                "SLACK",
                integration.integrationId(),
                eventId,
                payloadJson,
                UUID.randomUUID().toString());
        return Optional.empty();
    }

    private JsonNode parse(byte[] rawBody) {
        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            if (payload == null || !payload.isObject()) {
                throw ApiProblemException.validationFailed("Slack request body must be a JSON object.");
            }
            return payload;
        } catch (JacksonException exception) {
            throw ApiProblemException.validationFailed("Slack request body must be valid JSON.");
        }
    }

    private static CredentialReference selectIntegration(
            List<CredentialReference> verified,
            String teamId) {
        if (teamId != null) {
            List<CredentialReference> exact = verified.stream()
                    .filter(reference -> teamId.equals(reference.workspaceExternalId()))
                    .toList();
            if (exact.size() == 1) return exact.getFirst();
            if (exact.size() > 1) throw ApiProblemException.authenticationRequired();
        }

        List<CredentialReference> unbound = verified.stream()
                .filter(reference -> reference.workspaceExternalId() == null || reference.workspaceExternalId().isBlank())
                .toList();
        if (unbound.size() == 1) return unbound.getFirst();
        if (verified.size() == 1 && verified.getFirst().workspaceExternalId() == null) {
            return verified.getFirst();
        }
        throw ApiProblemException.authenticationRequired();
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value != null && value.isTextual() && !value.stringValue().isBlank()
                ? value.stringValue()
                : null;
    }

    private static String requiredText(JsonNode payload, String field, String detail) {
        String value = text(payload, field);
        if (value == null) throw ApiProblemException.validationFailed(detail);
        return value;
    }
}
