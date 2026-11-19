package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/provider-callbacks/teams")
class TeamsWebhookController {
    private final TeamsJwtAuthenticator authenticator;
    private final TeamsInboundDeliveryService deliveries;
    private final ProviderIntegrationCredentialLookup integrations;
    private final ObjectMapper json;
    TeamsWebhookController(TeamsJwtAuthenticator authenticator, TeamsInboundDeliveryService deliveries,
            ProviderIntegrationCredentialLookup integrations, ObjectMapper json) {
        this.authenticator = authenticator; this.deliveries = deliveries; this.integrations = integrations; this.json = json;
    }
    @PostMapping void receive(@RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody byte[] body) {
        if (body.length > 1048576) throw ApiProblemException.validationFailed("Teams activity exceeds the supported size.");
        tools.jackson.databind.JsonNode activity;
        try { activity = json.readTree(body); }
        catch (RuntimeException malformed) { throw ApiProblemException.validationFailed("Teams activity must be valid JSON."); }
        if (activity == null || !activity.isObject()) throw ApiProblemException.validationFailed("Teams activity must be an object.");
        authenticator.authenticate(authorization, activity);
        String tenant = activity.path("channelData").path("tenant").path("id").asText();
        var matches = integrations.findEnabledForProvider(IntegrationProvider.TEAMS).stream()
                .filter(i -> tenant.equals(i.workspaceExternalId())).toList();
        if (matches.size() != 1) throw ApiProblemException.authenticationRequired();
        try {
            String id = TeamsActivity.text(activity, "id");
            String conversation = TeamsActivity.text(activity.path("conversation"), "id");
            String type = TeamsActivity.text(activity, "type");
            // IDs are scoped to a conversation. Updates carry a timestamp so they don't dedup with create.
            String identity = conversation + ":" + type + ":" + id
                    + ("messageUpdate".equals(type) ? ":" + TeamsActivity.text(activity, "timestamp") : "");
            String key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
            deliveries.persistAndWake(matches.getFirst().integrationId(), key,
                    new String(body, StandardCharsets.UTF_8), UUID.randomUUID().toString());
        } catch (IllegalArgumentException malformed) {
            throw ApiProblemException.validationFailed("Teams activity identity is invalid.");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
