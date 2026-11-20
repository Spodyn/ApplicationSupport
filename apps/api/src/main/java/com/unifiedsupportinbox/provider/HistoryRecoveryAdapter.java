package com.unifiedsupportinbox.provider;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** One external page per durable task claim; provider I/O must precede the write transaction. */
public interface HistoryRecoveryAdapter {
    String provider();
    Page fetch(Task task);
    UUID enqueue(Task task, JsonNode message);

    record Task(UUID id, UUID runId, UUID integrationId, UUID channelId, String externalChannelId,
            String provider, String source, Instant from, Instant until, String thread,
            String cursor, UUID lease, JsonNode metadata) {
        public boolean includes(Instant sent) {
            return (from == null || !sent.isBefore(from)) && !sent.isAfter(until);
        }
    }
    record Page(List<JsonNode> messages, List<String> threads, String nextCursor, int scanned) {
        public Page(List<JsonNode> messages, List<String> threads, String nextCursor) {
            this(messages, threads, nextCursor, messages.size());
        }
        public Page { messages = List.copyOf(messages); threads = List.copyOf(threads); }
    }
    final class Unavailable extends RuntimeException {
        public final String code;
        public final Duration retryAfter;
        public Unavailable(String code, Duration retryAfter) {
            super(code); this.code = code; this.retryAfter = retryAfter;
        }
    }
}
