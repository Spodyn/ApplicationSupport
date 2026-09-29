package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.integration.IntegrationHealthReporter;
import com.unifiedsupportinbox.integration.IntegrationHealthReporter.FailureKind;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import com.unifiedsupportinbox.storage.AttachmentQuarantinedException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "usi.providers.slack.outbound.enabled",
        havingValue = "true",
        matchIfMissing = true)
class SlackMessageDeliveryProvider implements MessageDeliveryProvider {

    static final String BOT_TOKEN_CREDENTIAL_FILE = "slack-bot-token";

    private static final Set<String> DISCONNECT_ERROR_CODES = Set.of(
            "SLACK_ACCOUNT_INACTIVE",
            "SLACK_BOT_TOKEN_MISSING",
            "SLACK_CREDENTIAL_REFERENCE_MISSING",
            "SLACK_HTTP_401",
            "SLACK_HTTP_403",
            "SLACK_INVALID_AUTH",
            "SLACK_MISSING_SCOPE",
            "SLACK_NO_PERMISSION",
            "SLACK_NOT_AUTHED",
            "SLACK_TEAM_ACCESS_NOT_GRANTED",
            "SLACK_TOKEN_REVOKED");

    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final SlackWebApiClient slack;
    private final SlackOutboundAttachmentService attachments;
    private final IntegrationHealthReporter integrationHealth;

    SlackMessageDeliveryProvider(
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,
            SlackWebApiClient slack) {
        this(integrations, secrets, slack, null, null);
    }

    @Autowired
    SlackMessageDeliveryProvider(
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,
            SlackWebApiClient slack,
            SlackOutboundAttachmentService attachments,
            IntegrationHealthReporter integrationHealth) {
        this.integrations = integrations;
        this.secrets = secrets;
        this.slack = slack;
        this.attachments = attachments;
        this.integrationHealth = integrationHealth;
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
            reportFailure(command.integrationId(), "SLACK_CREDENTIAL_REFERENCE_MISSING");
            return DeliveryResult.permanentFailure("SLACK_CREDENTIAL_REFERENCE_MISSING");
        }

        byte[] token = secrets.resolve(integration.secretRef(), BOT_TOKEN_CREDENTIAL_FILE).orElse(null);
        if (token == null) {
            reportFailure(command.integrationId(), "SLACK_BOT_TOKEN_MISSING");
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
            DeliveryResult messageResult = map(response);
            if (messageResult.outcome() != MessageDeliveryProvider.Outcome.SENT
                    && messageResult.outcome() != MessageDeliveryProvider.Outcome.DELIVERED) {
                reportResult(command.integrationId(), messageResult);
                return messageResult;
            }
            if (attachments != null) {
                try {
                    attachments.uploadAll(command);
                } catch (ProviderAttachmentException attachmentFailure) {
                    DeliveryResult result = attachmentFailure.retryable()
                            ? DeliveryResult.transientFailure(
                                    attachmentFailure.errorCode(), attachmentFailure.retryAfter())
                            : DeliveryResult.permanentFailure(attachmentFailure.errorCode());
                    reportResult(command.integrationId(), result);
                    return result;
                } catch (AttachmentQuarantinedException quarantined) {
                    return DeliveryResult.permanentFailure("ATTACHMENT_NOT_CLEAN");
                }
            }
            reportRecovered(command.integrationId());
            return messageResult;
        } catch (RuntimeException failure) {
            reportDegraded(command.integrationId(), "SLACK_REQUEST_FAILED");
            throw failure;
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private void reportResult(java.util.UUID integrationId, DeliveryResult result) {
        if (integrationHealth == null || result == null) return;
        if (result.outcome() == MessageDeliveryProvider.Outcome.SENT
                || result.outcome() == MessageDeliveryProvider.Outcome.DELIVERED) {
            reportRecovered(integrationId);
            return;
        }
        String code = result.errorCode();
        if (code != null && DISCONNECT_ERROR_CODES.contains(code)) {
            integrationHealth.providerFailure(integrationId, code, FailureKind.DISCONNECTED);
            return;
        }
        if (result.outcome() == MessageDeliveryProvider.Outcome.TRANSIENT_FAILURE) {
            reportDegraded(integrationId, code);
        }
    }

    private void reportFailure(java.util.UUID integrationId, String code) {
        if (integrationHealth == null) return;
        integrationHealth.providerFailure(integrationId, code, FailureKind.DISCONNECTED);
    }

    private void reportDegraded(java.util.UUID integrationId, String code) {
        if (integrationHealth == null) return;
        integrationHealth.providerFailure(integrationId, code, FailureKind.DEGRADED);
    }

    private void reportRecovered(java.util.UUID integrationId) {
        if (integrationHealth != null) integrationHealth.providerRecovered(integrationId);
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
