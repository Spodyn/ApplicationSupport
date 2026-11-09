package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.channel.ChannelDiscoveryCatalog;
import com.unifiedsupportinbox.channel.ChannelGroupingStrategy;
import com.unifiedsupportinbox.channel.ChannelView;
import com.unifiedsupportinbox.channel.DiscoveredChannel;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@org.springframework.stereotype.Service
class SlackChannelDiscoveryService {

    private static final int MAX_PAGES = 100;
    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final SlackAdministrationApiClient slack;
    private final ChannelDiscoveryCatalog channels;
    private final ObjectMapper objectMapper;

    SlackChannelDiscoveryService(
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,
            SlackAdministrationApiClient slack,
            ChannelDiscoveryCatalog channels,
            ObjectMapper objectMapper) {
        this.integrations = integrations;
        this.secrets = secrets;
        this.slack = slack;
        this.channels = channels;
        this.objectMapper = objectMapper;
    }

    synchronized List<ChannelView> discover(UUID integrationId) {
        CredentialReference integration = integrations.findForProvider(IntegrationProvider.SLACK).stream()
                .filter(reference -> reference.integrationId().equals(integrationId))
                .findFirst()
                .orElseThrow(() -> ApiProblemException.notFound("Active Slack integration was not found."));
        byte[] token = secrets.resolve(
                        integration.secretRef(), SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE)
                .orElseThrow(() -> ApiProblemException.conflict("Slack bot token is not available at the configured secret reference."));
        try {
            verifyWorkspace(integration, token);
            List<DiscoveredChannel> discovered = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                SlackAdministrationApiClient.ConversationsResponse response =
                        slack.listConversations(token, integration.workspaceExternalId(), cursor);
                if (!response.ok()) {
                    throw ApiProblemException.conflict(
                            "Slack channel discovery failed with " + normalized(response.errorCode()) + ".");
                }
                response.channels().stream()
                        .filter(SlackAdministrationApiClient.Conversation::member)
                        .map(channel -> discovered(integrationId, channel))
                        .forEach(discovered::add);
                cursor = response.nextCursor();
                if (cursor == null || cursor.isBlank()) {
                    return channels.replace(integrationId, discovered);
                }
            }
            throw ApiProblemException.conflict("Slack channel discovery exceeded the pagination safety limit.");
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ApiProblemException.providerFailure(exception);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private void verifyWorkspace(CredentialReference integration, byte[] token) {
        SlackAdministrationApiClient.AuthResponse response = slack.testAuthentication(token);
        if (!response.ok()) {
            throw ApiProblemException.conflict("Slack authentication failed with " + normalized(response.errorCode()) + ".");
        }
        if (!integration.workspaceExternalId().equals(response.workspaceId())) {
            throw ApiProblemException.conflict("Slack credentials belong to a different workspace.");
        }
    }

    private DiscoveredChannel discovered(
            UUID integrationId, SlackAdministrationApiClient.Conversation channel) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("private", channel.privateChannel());
        metadata.put("slackConnect", channel.slackConnect());
        try {
            return new DiscoveredChannel(
                    integrationId,
                    channel.id(),
                    channel.name(),
                    ChannelGroupingStrategy.SLACK_ROOT_THREAD,
                    true,
                    null,
                    objectMapper.writeValueAsString(metadata));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Slack channel metadata could not be serialized.", exception);
        }
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return "SLACK_INVALID_RESPONSE";
        String code = value.strip().replaceAll("[^A-Za-z0-9_.:-]+", "_").toUpperCase(java.util.Locale.ROOT);
        return code.startsWith("SLACK_") ? code : "SLACK_" + code;
    }

}
