package com.unifiedsupportinbox.provider.slack.internal;

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
import tools.jackson.databind.ObjectMapper;

class HttpSlackHistoryClientTests {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsBoundedHistoryWithCursorAndBearerCredential() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"ok":true,"messages":[{"ts":"1712000000.000001","user":"U1","text":"hello"}],
                     "response_metadata":{"next_cursor":"next-page"}}
                    """);
        });

        SlackHistoryClient.Page page = client().history(
                credential(), "C123", "cursor value", "1711000000.000000", "1713000000.000000", 15);

        assertThat(page.ok()).isTrue();
        assertThat(page.messages()).hasSize(1);
        assertThat(page.messages().getFirst().path("ts").asText()).isEqualTo("1712000000.000001");
        assertThat(page.nextCursor()).isEqualTo("next-page");
        assertThat(authorization.get()).isEqualTo("Bearer fixture-history-credential");
        assertThat(query.get())
                .contains("channel=C123")
                .contains("oldest=1711000000.000000")
                .contains("latest=1713000000.000000")
                .contains("inclusive=true")
                .contains("limit=15")
                .contains("cursor=cursor+value");
    }

    @Test
    void readsThreadRepliesUsingRootTimestamp() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        startServer(exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "{\"ok\":true,\"messages\":[],\"response_metadata\":{\"next_cursor\":\"\"}}");
        });

        SlackHistoryClient.Page page = client().replies(
                credential(), "C123", "1712000000.000001", null,
                "1711000000.000000", "1713000000.000000", 10);

        assertThat(page.ok()).isTrue();
        assertThat(query.get()).contains("ts=1712000000.000001").contains("limit=10");
    }

    @Test
    void exposesRetryAfterWithoutParsingProviderPayloadOnRateLimit() throws Exception {
        startServer(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "75");
            respond(exchange, 429, "{\"ok\":false,\"error\":\"ratelimited\"}");
        });

        SlackHistoryClient.Page page = client().history(
                credential(), "C123", null, "1.000000000", "2.000000000", 15);

        assertThat(page.statusCode()).isEqualTo(429);
        assertThat(page.ok()).isFalse();
        assertThat(page.errorCode()).isEqualTo("rate_limited");
        assertThat(page.retryAfter()).isEqualTo(Duration.ofSeconds(75));
    }

    private HttpSlackHistoryClient client() {
        URI history = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/history");
        URI replies = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/replies");
        return new HttpSlackHistoryClient(HttpClient.newHttpClient(), json, history, replies);
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/history", exchange -> dispatch(exchange, handler));
        server.createContext("/api/replies", exchange -> dispatch(exchange, handler));
        server.start();
    }

    private static void dispatch(HttpExchange exchange, Handler handler) throws IOException {
        try {
            handler.handle(exchange);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Slack history fixture failed", exception);
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static byte[] credential() {
        return "fixture-history-credential".getBytes(StandardCharsets.US_ASCII);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
