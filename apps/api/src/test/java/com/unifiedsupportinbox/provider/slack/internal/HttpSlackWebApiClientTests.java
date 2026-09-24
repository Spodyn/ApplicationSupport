package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
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

class HttpSlackWebApiClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsJsonWithBearerTokenChannelThreadAndStableClientMessageId() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestJson = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestJson.set(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, "{\"ok\":true,\"channel\":\"C123\",\"ts\":\"1712345678.123456\"}");
        });

        SlackWebApiClient.PostMessageResponse response = client().postMessage(
                fixtureCredential(), "C123", "1712000000.000001", "Support *reply*",
                MessageBodyFormat.MARKDOWN, "b796a792-702f-4b70-ae65-d73892d548ac");

        assertThat(response.ok()).isTrue();
        assertThat(response.messageTs()).isEqualTo("1712345678.123456");
        assertThat(authorization.get()).isEqualTo("Bearer fixture-provider-credential");
        assertThat(requestJson.get().path("channel").asText()).isEqualTo("C123");
        assertThat(requestJson.get().path("thread_ts").asText()).isEqualTo("1712000000.000001");
        assertThat(requestJson.get().path("text").asText()).isEqualTo("Support *reply*");
        assertThat(requestJson.get().path("mrkdwn").asBoolean()).isTrue();
        assertThat(requestJson.get().path("client_msg_id").asText())
                .isEqualTo("b796a792-702f-4b70-ae65-d73892d548ac");
    }

    @Test
    void exposesSlackRetryAfterOnRateLimit() throws Exception {
        startServer(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "7");
            respond(exchange, 429, "{\"ok\":false,\"error\":\"ratelimited\"}");
        });

        SlackWebApiClient.PostMessageResponse response = client().postMessage(
                fixtureCredential(), "C123", null, "Plain reply",
                MessageBodyFormat.PLAIN_TEXT, "message-id");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.errorCode()).isEqualTo("rate_limited");
        assertThat(response.retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void returnsSlackApiErrorFromSuccessfulHttpResponse() throws Exception {
        startServer(exchange -> respond(exchange, 200, "{\"ok\":false,\"error\":\"channel_not_found\"}"));

        SlackWebApiClient.PostMessageResponse response = client().postMessage(
                fixtureCredential(), "C-missing", null, "Reply",
                MessageBodyFormat.PLAIN_TEXT, "message-id");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.ok()).isFalse();
        assertThat(response.errorCode()).isEqualTo("channel_not_found");
    }

    private HttpSlackWebApiClient client() {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat.postMessage");
        return new HttpSlackWebApiClient(HttpClient.newHttpClient(), objectMapper, endpoint);
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat.postMessage", exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IOException("Slack test handler failed", exception);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static byte[] fixtureCredential() {
        return ("fixture-" + "provider-credential").getBytes(StandardCharsets.US_ASCII);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
