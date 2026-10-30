package com.unifiedsupportinbox.provider.telegram.internal;

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

/** Sends one Telegram message per durable delivery attempt, without creating another local Message. */
@Component
@ConditionalOnProperty(name = "usi.providers.telegram.outbound.enabled", havingValue = "true", matchIfMissing = false)
class TelegramMessageDeliveryProvider implements MessageDeliveryProvider {

    static final String BOT_TOKEN_CREDENTIAL_FILE = "telegram-bot-token";

    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final TelegramBotApiClient telegram;

    TelegramMessageDeliveryProvider(
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,
            TelegramBotApiClient telegram) {
        this.integrations = integrations;
        this.secrets = secrets;
        this.telegram = telegram;
    }

    @Override
    public boolean supports(IntegrationProvider provider) {
        return provider == IntegrationProvider.TELEGRAM;
    }

    @Override
    public DeliveryResult deliver(DeliveryCommand command) {
        if (command == null || command.provider() != IntegrationProvider.TELEGRAM
                || command.integrationId() == null) {
            return DeliveryResult.permanentFailure("TELEGRAM_INVALID_COMMAND");
        }
        long chatId;
        Integer topic = null;
        try {
            chatId = Long.parseLong(command.externalConversationId());
            if (chatId == 0) return DeliveryResult.permanentFailure("TELEGRAM_CHAT_INVALID");
        } catch (NumberFormatException | NullPointerException invalid) {
            return DeliveryResult.permanentFailure("TELEGRAM_CHAT_INVALID");
        }
        if (command.externalThreadKey() != null && !command.externalThreadKey().isBlank()) {
            try {
                topic = Integer.valueOf(command.externalThreadKey());
                if (topic <= 0) return DeliveryResult.permanentFailure("TELEGRAM_TOPIC_INVALID");
            } catch (NumberFormatException invalid) {
                return DeliveryResult.permanentFailure("TELEGRAM_TOPIC_INVALID");
            }
        }
        if (command.body() == null || command.body().isBlank()
                || command.body().codePointCount(0, command.body().length()) > 4096) {
            return DeliveryResult.permanentFailure("TELEGRAM_TEXT_INVALID");
        }
        List<CredentialReference> matches = integrations.findForProvider(IntegrationProvider.TELEGRAM)
                .stream().filter(ref -> command.integrationId().equals(ref.integrationId())).toList();
        if (matches.size() != 1) {
            return DeliveryResult.permanentFailure("TELEGRAM_CREDENTIAL_REFERENCE_MISSING");
        }
        byte[] token = secrets.resolve(matches.getFirst().secretRef(), BOT_TOKEN_CREDENTIAL_FILE).orElse(null);
        if (token == null) return DeliveryResult.permanentFailure("TELEGRAM_BOT_TOKEN_MISSING");
        try {
            TelegramBotApiClient.SendResponse response = telegram.sendMessage(token, chatId, topic, command.body());
            return map(response);
        } catch (RuntimeException unexpected) {
            // HTTP libraries can include a token-bearing URL in exception messages.
            // Never propagate such details to the common worker logger.
            return DeliveryResult.transientFailure("TELEGRAM_REQUEST_FAILED", null);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private static DeliveryResult map(TelegramBotApiClient.SendResponse response) {
        if (response == null) return DeliveryResult.transientFailure("TELEGRAM_INVALID_RESPONSE", null);
        int status = response.httpStatus();
        int error = response.apiErrorCode() == null ? status : response.apiErrorCode();
        Duration delay = response.retryAfter();
        if (delay != null && (delay.isNegative() || delay.isZero())) delay = null;
        if (status == 429 || error == 429) {
            return DeliveryResult.transientFailure("TELEGRAM_RATE_LIMITED", delay);
        }
        if (status == 408 || status >= 500 || error == 408 || error >= 500) {
            return DeliveryResult.transientFailure("TELEGRAM_HTTP_" + status, delay);
        }
        if (status < 200 || status >= 300 || !response.ok()) {
            return DeliveryResult.permanentFailure("TELEGRAM_HTTP_" + error);
        }
        if (response.messageId() == null || response.messageId().isBlank()) {
            return DeliveryResult.transientFailure("TELEGRAM_MESSAGE_ID_MISSING", null);
        }
        return DeliveryResult.sent(response.messageId());
    }
}
