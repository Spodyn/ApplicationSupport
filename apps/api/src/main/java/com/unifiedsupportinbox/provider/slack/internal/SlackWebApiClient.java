package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import java.time.Duration;

interface SlackWebApiClient {

    default com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationResult mutate(byte[] token,
            com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationCommand command) {
        return com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationResult.failed("PROVIDER_UNSUPPORTED");
    }

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
            Duration retryAfter, String senderId) {
        PostMessageResponse(int statusCode,boolean ok,String messageTs,String errorCode,Duration retryAfter) {
            this(statusCode,ok,messageTs,errorCode,retryAfter,null);
        }
    }
}
