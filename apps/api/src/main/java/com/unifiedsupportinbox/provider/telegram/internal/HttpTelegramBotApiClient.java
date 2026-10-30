package com.unifiedsupportinbox.provider.telegram.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Telegram Bot API sendMessage; the credential stays server-side and never enters a log. */
@Component
class HttpTelegramBotApiClient implements TelegramBotApiClient {

    private static final URI API_ROOT = URI.create("https://api.telegram.org/");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI apiRoot;

    @Autowired
    HttpTelegramBotApiClient(ObjectMapper json) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json, API_ROOT);
    }

    HttpTelegramBotApiClient(HttpClient http, ObjectMapper json, URI apiRoot) {
        this.http = http;
        this.json = json;
        this.apiRoot = apiRoot;
    }

    @Override
    public SendResponse sendMessage(byte[] botToken, long chatId, Integer messageThreadId, String text) {
        // Telegram requires the bot token in the endpoint path. Never print this URI,
        // request, exception message or HTTP response body.
        String token = new String(botToken, StandardCharsets.US_ASCII);
        if (!token.matches("[0-9]+:[A-Za-z0-9_-]+")) {
            return new SendResponse(400, false, null, 400, null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        if (messageThreadId != null) payload.put("message_thread_id", messageThreadId);
        try {
            URI endpoint = apiRoot.resolve("./bot" + token + "/sendMessage");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)))
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            JsonNode body;
            try {
                body = json.readTree(response.body());
            } catch (JacksonException exception) {
                // A malformed provider body cannot erase a trustworthy HTTP error.
                int status = response.statusCode();
                return new SendResponse(status >= 200 && status < 300 ? 502 : status,
                        false, null, null, headerRetryAfter(response));
            }
            boolean ok = body != null && body.path("ok").asBoolean(false);
            JsonNode id = body == null ? null : body.path("result").path("message_id");
            String messageId = id != null && id.isIntegralNumber() ? id.asText() : null;
            JsonNode error = body == null ? null : body.path("error_code");
            Integer errorCode = error != null && error.isIntegralNumber() ? error.asInt() : null;
            JsonNode retry = body == null ? null : body.path("parameters").path("retry_after");
            Duration retryAfter = retry != null && retry.isIntegralNumber() && retry.asLong() > 0
                    ? Duration.ofSeconds(retry.asLong()) : headerRetryAfter(response);
            return new SendResponse(response.statusCode(), ok, messageId, errorCode, retryAfter);
        } catch (JacksonException exception) {
            // JSON serialization failed before the provider request could be sent.
            return new SendResponse(502, false, null, null, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new SendResponse(503, false, null, null, null);
        } catch (IOException exception) {
            return new SendResponse(503, false, null, null, null);
        }
    }

    private static Duration headerRetryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                return seconds > 0 ? java.util.Optional.of(Duration.ofSeconds(seconds))
                        : java.util.Optional.empty();
            } catch (NumberFormatException exception) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }
}
