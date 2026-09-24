package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import java.time.Duration;

interface SlackWebApiClient {

    PostMessageResponse postMessage(
            byte[] botToken,
            String channelId,
            String threadTs,
            String text,
            MessageBodyFormat bodyFormat,
            String clientMessageId);

    record PostMessageResponse(
            int statusCode,
            boolean ok,
            String messageTs,
            String errorCode,
            Duration retryAfter) {
    }
}
