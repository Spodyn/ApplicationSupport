package com.unifiedsupportinbox.provider.telegram.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class HttpTelegramBotApiClientTests {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOneTopicMessageWithChatIdAndThreadId() throws Exception {
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        start(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            payload.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, "{\"ok\":true,\"result\":{\"message_id\":321}}");
        });
        TelegramBotApiClient.SendResponse response = client().sendMessage(
                token(), -1001234567890L, 42, "Support reply");
        assertThat(response.ok()).isTrue();
        assertThat(response.messageId()).isEqualTo("321");
        assertThat(path.get()).isEqualTo("/bot123456789:fixtureToken123/sendMessage");
        assertThat(payload.get().path("chat_id").asLong()).isEqualTo(-1001234567890L);
        assertThat(payload.get().path("message_thread_id").asInt()).isEqualTo(42);
        assertThat(payload.get().path("text").asText()).isEqualTo("Support reply");
        assertThat(payload.get().has("parse_mode")).isFalse();
    }

    @Test
    void ordinaryChatOmitsTopicAndPreservesPlainText() throws Exception {
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        start(exchange -> {
            payload.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, "{\"ok\":true,\"result\":{\"message_id\":322}}");
        });
        assertThat(client().sendMessage(token(), 12345L, null, "Literal *markdown*").ok()).isTrue();
        assertThat(payload.get().has("message_thread_id")).isFalse();
        assertThat(payload.get().path("text").asText()).isEqualTo("Literal *markdown*");
    }

    @Test
    void parsesBotApiRateLimitDelayAndDoesNotTreatItAsSuccess() throws Exception {
        start(exchange -> respond(exchange, 429,
                "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":13}}"));
        TelegramBotApiClient.SendResponse response = client().sendMessage(token(), 12345L, null, "Reply");
        assertThat(response.ok()).isFalse();
        assertThat(response.httpStatus()).isEqualTo(429);
        assertThat(response.retryAfter()).isEqualTo(Duration.ofSeconds(13));
    }

    @Test
    void malformedProviderResponseCannotBeMistakenForSuccess() throws Exception {
        start(exchange -> respond(exchange, 200, "not-json"));
        TelegramBotApiClient.SendResponse response = client().sendMessage(token(), 12345L, null, "Reply");
        assertThat(response.ok()).isFalse();
        assertThat(response.httpStatus()).isEqualTo(502);
    }

    @Test
    void malformedRateLimitBodyStillPreservesHttp429AndRetryAfterHeader() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "17");
            respond(exchange, 429, "not-json");
        });
        TelegramBotApiClient.SendResponse response = client().sendMessage(token(), 12345L, null, "Reply");
        assertThat(response.ok()).isFalse();
        assertThat(response.httpStatus()).isEqualTo(429);
        assertThat(response.retryAfter()).isEqualTo(Duration.ofSeconds(17));
    }

    @Test
    void malformedForbiddenBodyRemainsPermanentHttp403() throws Exception {
        start(exchange -> respond(exchange, 403, "not-json"));
        TelegramBotApiClient.SendResponse response = client().sendMessage(token(), 12345L, null, "Reply");
        assertThat(response.ok()).isFalse();
        assertThat(response.httpStatus()).isEqualTo(403);
    }

    private HttpTelegramBotApiClient client() {
        return new HttpTelegramBotApiClient(HttpClient.newHttpClient(), json,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IOException("Test handler failed", exception);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static byte[] token() {
        return "123456789:fixtureToken123".getBytes(StandardCharsets.US_ASCII);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
