package com.unifiedsupportinbox.provider.telegram.internal;

import java.time.Duration;

/** Small outbound Bot API boundary; never expose bot credentials in responses or logs. */
interface TelegramBotApiClient {

    SendResponse sendMessage(byte[] botToken, long chatId, Integer messageThreadId, String text);

    record SendResponse(int httpStatus, boolean ok, String messageId, Integer apiErrorCode, Duration retryAfter) {
    }
}
