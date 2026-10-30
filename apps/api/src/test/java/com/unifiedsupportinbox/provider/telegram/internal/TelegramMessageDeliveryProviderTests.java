package com.unifiedsupportinbox.provider.telegram.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

class TelegramMessageDeliveryProviderTests {
    private ProviderIntegrationCredentialLookup integrations;
    private ConfiguredProviderSecretResolver secrets;
    private TelegramBotApiClient api;
    private TelegramMessageDeliveryProvider provider;
    private UUID integrationId;

    @BeforeEach
    void setUp() {
        integrations = mock(ProviderIntegrationCredentialLookup.class);
        secrets = mock(ConfiguredProviderSecretResolver.class);
        api = mock(TelegramBotApiClient.class);
        provider = new TelegramMessageDeliveryProvider(integrations, secrets, api);
        integrationId = UUID.randomUUID();
        when(integrations.findForProvider(IntegrationProvider.TELEGRAM)).thenReturn(List.of(
                new CredentialReference(integrationId, "bot-one", "telegram/development-bot")));
    }

    @Test
    void sendsToForumTopicAndReturnsProviderMessageId() {
        byte[] token = token();
        when(secrets.resolve("telegram/development-bot", TelegramMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(api.sendMessage(any(byte[].class), eq(-1001234567890L), eq(42), eq("Support reply")))
                .thenReturn(new TelegramBotApiClient.SendResponse(200, true, "789", null, null));

        MessageDeliveryProvider.DeliveryResult result = provider.deliver(command("42"));

        assertThat(provider.supports(IntegrationProvider.TELEGRAM)).isTrue();
        assertThat(provider.supports(IntegrationProvider.SLACK)).isFalse();
        assertThat(result.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.SENT);
        assertThat(result.providerMessageRef()).isEqualTo("789");
        assertThat(token).containsOnly((byte) 0);
        verify(api).sendMessage(any(byte[].class), eq(-1001234567890L), eq(42), eq("Support reply"));
    }

    @Test
    void sendsWithoutTopicForOrdinaryChat() {
        byte[] token = token();
        when(secrets.resolve("telegram/development-bot", TelegramMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(api.sendMessage(any(byte[].class), eq(-1001234567890L), eq(null), eq("Support reply")))
                .thenReturn(new TelegramBotApiClient.SendResponse(200, true, "790", null, null));
        assertThat(provider.deliver(command(null)).outcome()).isEqualTo(MessageDeliveryProvider.Outcome.SENT);
        assertThat(token).containsOnly((byte) 0);
    }

    @Test
    void propagatesTelegramRetryAfterThroughCommonRetryLifecycle() {
        byte[] token = token();
        when(secrets.resolve("telegram/development-bot", TelegramMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(api.sendMessage(any(), anyLong(), any(), any()))
                .thenReturn(new TelegramBotApiClient.SendResponse(429, false, null, 429, Duration.ofSeconds(11)));
        MessageDeliveryProvider.DeliveryResult result = provider.deliver(command("42"));
        assertThat(result.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.TRANSIENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("TELEGRAM_RATE_LIMITED");
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(11));
        assertThat(token).containsOnly((byte) 0);
    }

    @Test
    void permanentAuthFailureDoesNotRetry() {
        byte[] token = token();
        when(secrets.resolve("telegram/development-bot", TelegramMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(api.sendMessage(any(), anyLong(), any(), any()))
                .thenReturn(new TelegramBotApiClient.SendResponse(403, false, null, 403, null));
        MessageDeliveryProvider.DeliveryResult result = provider.deliver(command("42"));
        assertThat(result.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.PERMANENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("TELEGRAM_HTTP_403");
        assertThat(token).containsOnly((byte) 0);
    }

    @Test
    void invalidConversationAndTopicAreRejectedBeforeCredentialsOrHttp() {
        MessageDeliveryProvider.DeliveryCommand valid = command("42");
        MessageDeliveryProvider.DeliveryCommand invalidChat = new MessageDeliveryProvider.DeliveryCommand(
                valid.messageId(), valid.idempotencyKey(), valid.caseId(), valid.provider(),
                valid.integrationId(), "not-a-chat", valid.externalThreadKey(), valid.body(),
                valid.bodyFormat(), valid.correlationId());
        assertThat(provider.deliver(invalidChat).errorCode()).isEqualTo("TELEGRAM_CHAT_INVALID");
        assertThat(provider.deliver(command("not-a-topic")).errorCode()).isEqualTo("TELEGRAM_TOPIC_INVALID");
        verify(secrets, never()).resolve(any(), any());
        verify(api, never()).sendMessage(any(), anyLong(), any(), any());
    }

    @Test
    void tokenBearingTransportExceptionIsNotPropagatedToWorkerLogs() {
        byte[] token = token();
        when(secrets.resolve("telegram/development-bot", TelegramMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(api.sendMessage(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("https://api.telegram.org/bot123456789:secret/sendMessage"));
        MessageDeliveryProvider.DeliveryResult result = provider.deliver(command("42"));
        assertThat(result.outcome()).isEqualTo(MessageDeliveryProvider.Outcome.TRANSIENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("TELEGRAM_REQUEST_FAILED");
        assertThat(token).containsOnly((byte) 0);
    }

    @Test
    void missingBotCredentialIsPermanentWithoutProviderCall() {
        when(secrets.resolve("telegram/development-bot", TelegramMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.empty());
        assertThat(provider.deliver(command(null)).errorCode()).isEqualTo("TELEGRAM_BOT_TOKEN_MISSING");
        verify(api, never()).sendMessage(any(), anyLong(), any(), any());
    }

    private MessageDeliveryProvider.DeliveryCommand command(String topic) {
        return new MessageDeliveryProvider.DeliveryCommand(
                UUID.randomUUID(), "stable-message-id", UUID.randomUUID(), IntegrationProvider.TELEGRAM,
                integrationId, "-1001234567890", topic, "Support reply", MessageBodyFormat.PLAIN_TEXT, "corr-123");
    }

    private static byte[] token() {
        return "123456789:fixtureToken123".getBytes(StandardCharsets.US_ASCII);
    }
}
