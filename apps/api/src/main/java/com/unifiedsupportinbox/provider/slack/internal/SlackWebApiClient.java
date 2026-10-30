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

    /**
     * Opt-in channel broadcast uses the same single Slack post as the thread reply.
     * Existing provider clients keep their non-broadcast behavior. Providers that
     * cannot honor the requested broadcast must reject it, never silently drop it.
     */
    default PostMessageResponse postMessage(
            byte[] botToken,
            String channelId,
            String threadTs,
            String text,
            MessageBodyFormat bodyFormat,
            String clientMessageId,
            boolean replyBroadcast) {
        if (replyBroadcast) {
            throw new IllegalArgumentException("Reply broadcast is not supported by this provider client.");
        }
        return postMessage(botToken, channelId, threadTs, text, bodyFormat, clientMessageId);
    }

    record PostMessageResponse(
            int statusCode,
            boolean ok,
            String messageTs,
            String errorCode,
            Duration retryAfter) {
    }
}
