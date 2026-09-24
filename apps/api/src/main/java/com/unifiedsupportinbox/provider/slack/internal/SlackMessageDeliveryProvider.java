package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "usi.providers.slack.outbound.enabled",
        havingValue = "true",
        matchIfMissing = true)
class SlackMessageDeliveryProvider implements MessageDeliveryProvider {

    static final String BOT_TOKEN_CREDENTIAL_FILE = "slack-bot-token";

    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final SlackWebApiClient slack;

    SlackMessageDeliveryProvider(
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,
            SlackWebApiClient slack) {
        this.integrations = integrations;
        this.secrets = secrets;
        this.slack = slack;
    }

    @Override
    public boolean supports(IntegrationProvider provider) {
        return provider == IntegrationProvider.SLACK;
    }

    @Override
    public DeliveryResult deliver(DeliveryCommand command) {
        if (command == null || command.provider() != IntegrationProvider.SLACK) {
            return DeliveryResult.permanentFailure("SLACK_INVALID_COMMAND");
        }
        if (command.externalConversationId() == null || command.externalConversationId().isBlank()) {
            return DeliveryResult.permanentFailure("SLACK_CHANNEL_MISSING");
        }

        CredentialReference integration = credentialFor(command);
        if (integration == null) {
            return DeliveryResult.permanentFailure("SLACK_CREDENTIAL_REFERENCE_MISSING");
        }

        byte[] token = secrets.resolve(integration.secretRef(), BOT_TOKEN_CREDENTIAL_FILE).orElse(null);
        if (token == null) {
            return DeliveryResult.permanentFailure("SLACK_BOT_TOKEN_MISSING");
        }

        try {
            SlackWebApiClient.PostMessageResponse response = slack.postMessage(
                    token,
                    command.externalConversationId(),
                    command.externalThreadKey(),
                    command.body(),
                    command.bodyFormat(),
                    command.idempotencyKey());
            return map(response);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private CredentialReference credentialFor(DeliveryCommand command) {
        List<CredentialReference> matches = integrations.findForProvider(IntegrationProvider.SLACK).stream()
                .filter(reference -> command.integrationId().equals(reference.integrationId()))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static DeliveryResult map(SlackWebApiClient.PostMessageResponse response) {
        if (response == null) {
            return DeliveryResult.transientFailure("SLACK_INVALID_RESPONSE", null);
        }
        if (response.statusCode() == 429) {
            return DeliveryResult.transientFailure("SLACK_RATE_LIMITED", positive(response.retryAfter()));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String code = "SLACK_HTTP_" + response.statusCode();
            if (SlackDeliveryErrorClassifier.isTransientHttpStatus(response.statusCode())) {
                return DeliveryResult.transientFailure(code, positive(response.retryAfter()));
            }
            return DeliveryResult.permanentFailure(code);
        }
        if (!response.ok()) {
            String code = SlackDeliveryErrorClassifier.normalizedApiError(response.errorCode());
            if (SlackDeliveryErrorClassifier.isTransientApiError(response.errorCode())) {
                return DeliveryResult.transientFailure(code, positive(response.retryAfter()));
            }
            return DeliveryResult.permanentFailure(code);
        }
        if (response.messageTs() == null || response.messageTs().isBlank()) {
            return DeliveryResult.transientFailure("SLACK_RESPONSE_TS_MISSING", null);
        }
        return DeliveryResult.sent(response.messageTs());
    }

    private static Duration positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero() ? value : null;
    }
}
