package com.unifiedsupportinbox.provider.slack.internal;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class HttpSlackHistoryClient implements SlackHistoryClient {

    private static final URI DEFAULT_HISTORY = URI.create("https://slack.com/api/conversations.history");
    private static final URI DEFAULT_REPLIES = URI.create("https://slack.com/api/conversations.replies");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_PAGE_SIZE = 15;

    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final URI historyEndpoint;
    private final URI repliesEndpoint;

    @Autowired
    HttpSlackHistoryClient(ObjectMapper json) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                json,
                DEFAULT_HISTORY,
                DEFAULT_REPLIES);
    }

    HttpSlackHistoryClient(
            HttpClient httpClient,
            ObjectMapper json,
            URI historyEndpoint,
            URI repliesEndpoint) {
        this.httpClient = httpClient;
        this.json = json;
        this.historyEndpoint = historyEndpoint;
        this.repliesEndpoint = repliesEndpoint;
    }

    @Override
    public Page history(
            byte[] botToken,
            String channelId,
            String cursor,
            String oldestTs,
            String latestTs,
            int limit) {
        Map<String, String> query = commonQuery(channelId, cursor, oldestTs, latestTs, limit);
        return get(botToken, historyEndpoint, query);
    }

    @Override
    public Page replies(
            byte[] botToken,
            String channelId,
            String threadTs,
            String cursor,
            String oldestTs,
            String latestTs,
            int limit) {
        Map<String, String> query = commonQuery(channelId, cursor, oldestTs, latestTs, limit);
        requireText(threadTs, "threadTs");
        query.put("ts", threadTs);
        return get(botToken, repliesEndpoint, query);
    }

    private Page get(byte[] botToken, URI endpoint, Map<String, String> query) {
        if (botToken == null || botToken.length == 0) {
            throw new IllegalArgumentException("Slack bot token is required.");
        }

        byte[] authorization = null;
        try {
            URI target = URI.create(endpoint + "?" + encodedQuery(query));
            authorization = new byte["Bearer ".length() + botToken.length];
            System.arraycopy(
                    "Bearer ".getBytes(StandardCharsets.US_ASCII),
                    0,
                    authorization,
                    0,
                    "Bearer ".length());
            System.arraycopy(botToken, 0, authorization, "Bearer ".length(), botToken.length);

            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", new String(authorization, StandardCharsets.US_ASCII))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            Duration retryAfter = retryAfter(response);

            if (response.statusCode() == 429) {
                return new Page(429, false, List.of(), null, "rate_limited", retryAfter);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new Page(
                        response.statusCode(),
                        false,
                        List.of(),
                        null,
                        "http_" + response.statusCode(),
                        retryAfter);
            }

            JsonNode root = json.readTree(response.body());
            boolean ok = root != null && root.path("ok").asBoolean(false);
            String error = text(root, "error");
            List<JsonNode> messages = messages(root);
            String nextCursor = null;
            JsonNode metadata = root == null ? null : root.get("response_metadata");
            if (metadata != null && metadata.isObject()) {
                nextCursor = text(metadata, "next_cursor");
            }
            return new Page(response.statusCode(), ok, messages, nextCursor, error, retryAfter);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Slack history JSON could not be processed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Slack history request was interrupted.", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Slack history request failed.", exception);
        } finally {
            if (authorization != null) Arrays.fill(authorization, (byte) 0);
        }
    }

    private static Map<String, String> commonQuery(
            String channelId,
            String cursor,
            String oldestTs,
            String latestTs,
            int limit) {
        requireText(channelId, "channelId");
        requireText(oldestTs, "oldestTs");
        requireText(latestTs, "latestTs");
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Slack history page limit must be between 1 and 15.");
        }

        Map<String, String> query = new LinkedHashMap<>();
        query.put("channel", channelId);
        query.put("oldest", oldestTs);
        query.put("latest", latestTs);
        query.put("inclusive", "true");
        query.put("limit", Integer.toString(limit));
        if (cursor != null && !cursor.isBlank()) query.put("cursor", cursor);
        return query;
    }

    private static String encodedQuery(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<JsonNode> messages(JsonNode root) {
        JsonNode messages = root == null ? null : root.get("messages");
        if (messages == null || !messages.isArray()) return List.of();
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode message : messages) {
            if (message != null && message.isObject()) result.add(message);
        }
        return List.copyOf(result);
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        long seconds = Long.parseLong(value.trim());
                        return seconds > 0
                                ? java.util.Optional.of(Duration.ofSeconds(seconds))
                                : java.util.Optional.empty();
                    } catch (NumberFormatException exception) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(null);
    }

    private static String text(JsonNode object, String field) {
        if (object == null) return null;
        JsonNode value = object.get(field);
        return value != null && value.isTextual() && !value.stringValue().isBlank()
                ? value.stringValue()
                : null;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
    }
}
