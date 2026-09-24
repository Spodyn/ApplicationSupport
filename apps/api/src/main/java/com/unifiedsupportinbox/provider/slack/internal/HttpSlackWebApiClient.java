package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class HttpSlackWebApiClient implements SlackWebApiClient {

    private static final URI DEFAULT_CHAT_POST_MESSAGE = URI.create("https://slack.com/api/chat.postMessage");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI chatPostMessageEndpoint;

    @Autowired
    HttpSlackWebApiClient(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build(), objectMapper, DEFAULT_CHAT_POST_MESSAGE);
    }

    HttpSlackWebApiClient(HttpClient httpClient, ObjectMapper objectMapper, URI chatPostMessageEndpoint) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.chatPostMessageEndpoint = chatPostMessageEndpoint;
    }

    @Override
    public PostMessageResponse postMessage(
            byte[] botToken,
            String channelId,
            String threadTs,
            String text,
            MessageBodyFormat bodyFormat,
            String clientMessageId) {
        if (botToken == null || botToken.length == 0) {
            throw new IllegalArgumentException("Slack bot token is required.");
        }

        byte[] authorization = null;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("channel", channelId);
            payload.put("text", text);
            payload.put("mrkdwn", bodyFormat == MessageBodyFormat.MARKDOWN);
            if (threadTs != null && !threadTs.isBlank()) payload.put("thread_ts", threadTs);
            if (clientMessageId != null && !clientMessageId.isBlank()) payload.put("client_msg_id", clientMessageId);

            byte[] body = objectMapper.writeValueAsBytes(payload);
            authorization = new byte["Bearer ".length() + botToken.length];
            System.arraycopy("Bearer ".getBytes(StandardCharsets.US_ASCII), 0, authorization, 0, "Bearer ".length());
            System.arraycopy(botToken, 0, authorization, "Bearer ".length(), botToken.length);

            HttpRequest request = HttpRequest.newBuilder(chatPostMessageEndpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", new String(authorization, StandardCharsets.US_ASCII))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            Duration retryAfter = retryAfter(response);
            if (response.statusCode() == 429) {
                return new PostMessageResponse(429, false, null, "rate_limited", retryAfter);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new PostMessageResponse(response.statusCode(), false, null, "http_" + response.statusCode(), retryAfter);
            }

            JsonNode json = objectMapper.readTree(response.body());
            boolean ok = json != null && json.path("ok").asBoolean(false);
            String ts = text(json, "ts");
            String error = text(json, "error");
            return new PostMessageResponse(response.statusCode(), ok, ts, error, retryAfter);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Slack Web API JSON could not be processed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Slack Web API call was interrupted.", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Slack Web API call failed.", exception);
        } finally {
            if (authorization != null) Arrays.fill(authorization, (byte) 0);
        }
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        long seconds = Long.parseLong(value.trim());
                        return seconds > 0 ? java.util.Optional.of(Duration.ofSeconds(seconds)) : java.util.Optional.empty();
                    } catch (NumberFormatException exception) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(null);
    }

    private static String text(JsonNode json, String field) {
        if (json == null) return null;
        JsonNode value = json.get(field);
        return value != null && value.isTextual() && !value.stringValue().isBlank()
                ? value.stringValue()
                : null;
    }
}
