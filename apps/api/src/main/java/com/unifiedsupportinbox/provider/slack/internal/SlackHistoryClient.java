package com.unifiedsupportinbox.provider.slack.internal;

import java.time.Duration;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Provider-only boundary for bounded Slack recovery/history reads. */
interface SlackHistoryClient {

    Page history(
            byte[] botToken,
            String channelId,
            String cursor,
            String oldestTs,
            String latestTs,
            int limit);

    Page replies(
            byte[] botToken,
            String channelId,
            String threadTs,
            String cursor,
            String oldestTs,
            String latestTs,
            int limit);

    record Page(
            int statusCode,
            boolean ok,
            List<JsonNode> messages,
            String nextCursor,
            String errorCode,
            Duration retryAfter) {

        public Page {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
}
