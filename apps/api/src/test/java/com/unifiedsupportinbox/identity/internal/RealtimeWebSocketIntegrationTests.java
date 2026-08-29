package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class RealtimeWebSocketIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static ConfigurableApplicationContext context;
    private static UserAccountRepository users;
    private static PasswordEncoder encoder;
    private static JdbcTemplate jdbc;
    private static URI baseUri;
    private static URI websocketUri;
    private static String sameOrigin;

    @BeforeAll
    static void startApplication() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.session.jdbc.initialize-schema=never",
                        "--usi.bootstrap-admin.enabled=false",
                        "--usi.realtime.heartbeat=100ms",
                        "--usi.realtime.time-to-first-message=500ms");

        users = context.getBean(UserAccountRepository.class);
        encoder = context.getBean("bootstrapAdminPasswordEncoder", PasswordEncoder.class);
        jdbc = context.getBean(JdbcTemplate.class);
        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null) {
            throw new IllegalStateException("random server port was not published");
        }
        baseUri = URI.create("http://127.0.0.1:" + port);
        websocketUri = URI.create("ws://127.0.0.1:" + port + "/ws");
        sameOrigin = baseUri.toString();
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) {
            context.close();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update(
                "UPDATE bootstrap_admin_state "
                        + "SET consumed = FALSE, consumed_at = NULL, admin_user_id = NULL "
                        + "WHERE id = 1");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void authenticatedSessionNegotiatesStompAndHeartbeatWithoutQueryCredentials() throws Exception {
        createUser("realtime@example.com");
        SessionClient session = login("realtime@example.com");
        RealtimeListener listener = new RealtimeListener();

        WebSocket socket = connect(session.httpClient(), session.cookies(), websocketUri, sameOrigin, listener);
        socket.sendText(stompConnect("0,100"), true).join();

        String connected = listener.connectedFrame().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(connected)
                .startsWith("CONNECTED\n")
                .contains("version:1.2")
                .contains("heart-beat:100,100");
        assertThat(websocketUri.getQuery()).isNull();

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join();
    }

    @Test
    void unauthenticatedAndExpiredSessionsCannotUpgrade() throws Exception {
        CookieManager anonymousCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient anonymous = client(anonymousCookies);
        assertThat(handshakeStatus(anonymous, anonymousCookies, websocketUri, sameOrigin)).isEqualTo(401);

        UserAccount user = createUser("expired-realtime@example.com");
        SessionClient session = login("expired-realtime@example.com");
        user.setValidityWindow(Instant.now().minusSeconds(3600), Instant.now().minusMillis(1));
        users.saveAndFlush(user);

        assertThat(handshakeStatus(session.httpClient(), session.cookies(), websocketUri, sameOrigin))
                .isEqualTo(401);
        assertThat(queryInt("SELECT COUNT(*) FROM spring_session")).isZero();
    }

    @Test
    void crossOriginAndQueryStringCredentialsAreRejected() throws Exception {
        createUser("origin@example.com");
        SessionClient session = login("origin@example.com");

        assertThat(handshakeStatus(
                session.httpClient(),
                session.cookies(),
                websocketUri,
                "https://evil.example"))
                .isEqualTo(403);

        URI queryCredential = URI.create(websocketUri + "?access_token=must-not-be-supported");
        assertThat(handshakeStatus(
                session.httpClient(), session.cookies(), queryCredential, sameOrigin))
                .isEqualTo(400);
    }

    @Test
    void connectionWithoutFirstStompFrameIsClosedByTransportTimeout() throws Exception {
        createUser("first-frame@example.com");
        SessionClient session = login("first-frame@example.com");
        RealtimeListener listener = new RealtimeListener();

        connect(session.httpClient(), session.cookies(), websocketUri, sameOrigin, listener);

        CloseFrame close = listener.closed().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(close.statusCode()).isNotEqualTo(WebSocket.NORMAL_CLOSURE);
    }

    @Test
    void missingClientHeartbeatClosesConnectionAndSameSessionCanReconnect() throws Exception {
        createUser("heartbeat@example.com");
        SessionClient session = login("heartbeat@example.com");
        RealtimeListener firstListener = new RealtimeListener();

        WebSocket first = connect(session.httpClient(), session.cookies(), websocketUri, sameOrigin, firstListener);
        first.sendText(stompConnect("100,0"), true).join();
        assertThat(firstListener.connectedFrame().get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .startsWith("CONNECTED\n");

        CloseFrame heartbeatClose = firstListener.closed().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(heartbeatClose.statusCode()).isNotEqualTo(WebSocket.NORMAL_CLOSURE);

        RealtimeListener secondListener = new RealtimeListener();
        WebSocket second = connect(session.httpClient(), session.cookies(), websocketUri, sameOrigin, secondListener);
        second.sendText(stompConnect("0,100"), true).join();
        assertThat(secondListener.connectedFrame().get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .startsWith("CONNECTED\n");
        second.sendClose(WebSocket.NORMAL_CLOSURE, "reconnected").join();
    }

    private static UserAccount createUser(String email) {
        return users.saveAndFlush(new UserAccount(
                email,
                "Realtime Test",
                encoder.encode(testCredential()),
                UserRole.USER,
                true,
                null,
                null));
    }

    private static SessionClient login(String email) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient httpClient = client(cookies);
        primeCsrf(httpClient, cookies);
        HttpResponse<String> response = postJson(
                httpClient,
                "/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + testCredential() + "\"}",
                cookieValue(cookies, "XSRF-TOKEN"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(cookieValue(cookies, "USI_SESSION")).isNotBlank();
        return new SessionClient(httpClient, cookies);
    }

    private static void primeCsrf(HttpClient httpClient, CookieManager cookies) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/me"))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(cookieValue(cookies, "XSRF-TOKEN")).isNotBlank();
    }

    private static HttpResponse<String> postJson(
            HttpClient client,
            String path,
            String body,
            String csrfToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static WebSocket connect(
            HttpClient httpClient,
            CookieManager cookies,
            URI uri,
            String origin,
            RealtimeListener listener) throws Exception {
        return httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .header("Origin", origin)
                .header("Cookie", "USI_SESSION=" + cookieValue(cookies, "USI_SESSION"))
                .buildAsync(uri, listener)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static int handshakeStatus(
            HttpClient httpClient,
            CookieManager cookies,
            URI uri,
            String origin) {
        try {
            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .header("Origin", origin)
                    .header("Cookie", cookieHeader(cookies))
                    .buildAsync(uri, new RealtimeListener())
                    .join();
            throw new AssertionError("Expected WebSocket handshake to fail");
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof WebSocketHandshakeException handshake) {
                return handshake.getResponse().statusCode();
            }
            throw exception;
        }
    }

    private static String cookieHeader(CookieManager cookies) {
        String sessionId = cookieValue(cookies, "USI_SESSION");
        return sessionId == null ? "" : "USI_SESSION=" + sessionId;
    }

    private static String cookieValue(CookieManager cookies, String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> name.equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private static HttpClient client(CookieManager cookies) {
        return HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String stompConnect(String heartbeat) {
        return "CONNECT\naccept-version:1.2\nheart-beat:" + heartbeat + "\n\n\u0000";
    }

    private static String testCredential() {
        return String.join("-", "test", "only", "realtime", "credential");
    }

    private static int queryInt(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private record SessionClient(HttpClient httpClient, CookieManager cookies) {
    }

    private record CloseFrame(int statusCode, String reason) {
    }

    private static final class RealtimeListener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private final CompletableFuture<String> connectedFrame = new CompletableFuture<>();
        private final CompletableFuture<CloseFrame> closed = new CompletableFuture<>();

        CompletableFuture<String> connectedFrame() {
            return connectedFrame;
        }

        CompletableFuture<CloseFrame> closed() {
            return closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last) {
            text.append(data);
            if (last) {
                String value = text.toString();
                int end = value.indexOf('\u0000');
                if (end >= 0) {
                    String frame = value.substring(0, end);
                    text.delete(0, end + 1);
                    if (frame.stripLeading().startsWith("CONNECTED\n")) {
                        connectedFrame.complete(frame.stripLeading());
                    }
                } else if (value.chars().allMatch(character -> character == '\n' || character == '\r')) {
                    text.setLength(0);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return WebSocket.Listener.super.onPing(webSocket, message);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(
                WebSocket webSocket,
                int statusCode,
                String reason) {
            closed.complete(new CloseFrame(statusCode, reason));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            connectedFrame.completeExceptionally(error);
            closed.complete(new CloseFrame(-1, error.getClass().getSimpleName()));
        }
    }
}
