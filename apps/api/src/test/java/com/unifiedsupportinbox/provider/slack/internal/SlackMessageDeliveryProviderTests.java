package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlackMessageDeliveryProviderTests {

    private ProviderIntegrationCredentialLookup integrations;
    private ConfiguredProviderSecretResolver secrets;
    private SlackWebApiClient slack;
    private SlackMessageDeliveryProvider provider;
    private UUID integrationId;

    @BeforeEach
    void setUp() {
        integrations = mock(ProviderIntegrationCredentialLookup.class);
        secrets = mock(ConfiguredProviderSecretResolver.class);
        slack = mock(SlackWebApiClient.class);
        provider = new SlackMessageDeliveryProvider(integrations, secrets, slack);
        integrationId = UUID.randomUUID();
        when(integrations.findForProvider(IntegrationProvider.SLACK)).thenReturn(List.of(
                new CredentialReference(integrationId, "T123", "slack/workspace-one")));
    }

    @Test
    void sendsSlackThreadReplyAndReturnsProviderTimestamp() {
        byte[] token = fixtureCredential();
        when(secrets.resolve("slack/workspace-one", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(slack.postMessage(
                any(byte[].class), eq("C123"), eq("1712000000.000001"), eq("Support reply"),
                eq(MessageBodyFormat.PLAIN_TEXT), eq("stable-message-id")))
                .thenReturn(new SlackWebApiClient.PostMessageResponse(
                        200, true, "1712345678.123456", null, null));

        MessageDeliveryProvider.DeliveryResult result = provider.deliver(command());

        assertThat(result.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.SENT);
        assertThat(result.providerMessageRef()).isEqualTo("1712345678.123456");
        assertThat(token).containsOnly((byte) 0);
        verify(slack).postMessage(
                any(byte[].class), eq("C123"), eq("1712000000.000001"), eq("Support reply"),
                eq(MessageBodyFormat.PLAIN_TEXT), eq("stable-message-id"));
    }

    @Test
    void propagatesSlackRateLimitAsTransientFailure() {
        byte[] token = fixtureCredential();
        when(secrets.resolve("slack/workspace-one", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(slack.postMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SlackWebApiClient.PostMessageResponse(
                        429, false, null, "rate_limited", Duration.ofSeconds(9)));

        MessageDeliveryProvider.DeliveryResult result = provider.deliver(command());

        assertThat(result.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.TRANSIENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("SLACK_RATE_LIMITED");
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(9));
        assertThat(token).containsOnly((byte) 0);
    }

    @Test
    void workspaceMigrationIsTransientFailure() {
        byte[] token = fixtureCredential();
        when(secrets.resolve("slack/workspace-one", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(slack.postMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SlackWebApiClient.PostMessageResponse(
                        200, false, null, "team_added_to_org", null));

        MessageDeliveryProvider.DeliveryResult result = provider.deliver(command());

        assertThat(result.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.TRANSIENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("SLACK_TEAM_ADDED_TO_ORG");
        assertThat(result.retryAfter()).isNull();
    }

    @Test
    void gatewayTimeoutIsTransientFailure() {
        byte[] token = fixtureCredential();
        when(secrets.resolve("slack/workspace-one", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(slack.postMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SlackWebApiClient.PostMessageResponse(
                        408, false, null, "http_408", Duration.ofSeconds(3)));

        MessageDeliveryProvider.DeliveryResult result = provider.deliver(command());

        assertThat(result.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.TRANSIENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("SLACK_HTTP_408");
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void missingBotTokenIsPermanentConfigurationFailure() {
        when(secrets.resolve("slack/workspace-one", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.empty());

        MessageDeliveryProvider.DeliveryResult result = provider.deliver(command());

        assertThat(result.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.PERMANENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("SLACK_BOT_TOKEN_MISSING");
    }

    @Test
    void channelNotFoundIsPermanentFailure() {
        byte[] token = fixtureCredential();
        when(secrets.resolve("slack/workspace-one", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(slack.postMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SlackWebApiClient.PostMessageResponse(
                        200, false, null, "channel_not_found", null));

        MessageDeliveryProvider.DeliveryResult result = provider.deliver(command());

        assertThat(result.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.PERMANENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("SLACK_CHANNEL_NOT_FOUND");
    }

    private MessageDeliveryProvider.DeliveryCommand command() {
        return new MessageDeliveryProvider.DeliveryCommand(
                UUID.randomUUID(), "stable-message-id", UUID.randomUUID(), IntegrationProvider.SLACK,
                integrationId, "C123", "1712000000.000001", "Support reply",
                MessageBodyFormat.PLAIN_TEXT, "corr-123");
    }

    private static byte[] fixtureCredential() {
        return ("fixture-" + "provider-credential").getBytes(StandardCharsets.US_ASCII);
    }
}
