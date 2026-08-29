package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class SlackWebhookIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final String SECRET_REF = "slack/development-workspace";
    private static final String TEAM_ID = "T-DEVELOPMENT";

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static URI baseUri;
    private static Path secretRoot;

    @BeforeAll
    static void startApplication() throws Exception {
        secretRoot = Files.createTempDirectory("usi-slack-integration-secrets-");
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
                        "--usi.integration-secrets.backend=filesystem",
                        "--usi.integration-secrets.directory=" + secretRoot.toAbsolutePath());
        jdbc = context.getBean(JdbcTemplate.class);
        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        baseUri = URI.create("http://127.0.0.1:" + port);
    }

    @AfterAll
    static void stopApplication() throws Exception {
        if (context != null) context.close();
        POSTGRES.stop();
        if (secretRoot != null && Files.exists(secretRoot)) {
            try (var paths = Files.walk(secretRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Best-effort cleanup of disposable test-only secret files.
                    }
                });
            }
        }
    }

    @BeforeEach
    void resetState() throws Exception {
        jdbc.update("DELETE FROM inbound_events");
        jdbc.update("DELETE FROM channels");
        jdbc.update("DELETE FROM integrations");
        clearSecretRoot();
    }

    @Test
    void validUrlVerificationIsAuthenticatedBeforeReturningChallenge() throws Exception {
        createSlackIntegration(TEAM_ID);
        writeSigningSecret();
        String body = "{\"type\":\"url_verification\",\"team_id\":\"" + TEAM_ID
                + "\",\"challenge\":\"challenge-value\"}";
        String timestamp = currentTimestamp();

        HttpResponse<String> response = post(body, timestamp, sign(body, timestamp));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"challenge\":\"challenge-value\"");
        assertThat(countInboundEvents()).isZero();
    }

    @Test
    void eventCallbackIsDurableBeforeAckAndDuplicateDeliveryIsIdempotent() throws Exception {
        UUID integrationId = createSlackIntegration(TEAM_ID);
        writeSigningSecret();
        String body = eventBody("Ev-duplicate");
        String timestamp = currentTimestamp();
        String signature = sign(body, timestamp);

        HttpResponse<String> first = post(body, timestamp, signature);
        HttpResponse<String> duplicate = post(body, timestamp, signature);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(countInboundEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT integration_id FROM inbound_events WHERE external_event_id = 'Ev-duplicate'",
                        UUID.class))
                .isEqualTo(integrationId);
        assertThat(jdbc.queryForObject(
                        "SELECT provider FROM inbound_events WHERE external_event_id = 'Ev-duplicate'",
                        String.class))
                .isEqualTo("SLACK");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM inbound_events WHERE external_event_id = 'Ev-duplicate'",
                        String.class))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject(
                        "SELECT payload_json::text FROM inbound_events WHERE external_event_id = 'Ev-duplicate'",
                        String.class))
                .contains("\"event_id\": \"Ev-duplicate\"");
    }

    @Test
    void invalidSignatureStaleTimestampAndRawBodyTamperingAreRejectedWithoutPersistence() throws Exception {
        createSlackIntegration(TEAM_ID);
        writeSigningSecret();
        String body = eventBody("Ev-security");
        String now = currentTimestamp();

        HttpResponse<String> invalid = post(body, now, "v0=" + "0".repeat(64));
        assertThat(invalid.statusCode()).isEqualTo(401);

        String stale = Long.toString(Instant.now().minusSeconds(301).getEpochSecond());
        HttpResponse<String> staleResponse = post(body, stale, sign(body, stale));
        assertThat(staleResponse.statusCode()).isEqualTo(401);

        String signedBody = eventBody("Ev-signed");
        String tamperedBody = eventBody("Ev-tampered");
        HttpResponse<String> tampered = post(tamperedBody, now, sign(signedBody, now));
        assertThat(tampered.statusCode()).isEqualTo(401);

        assertThat(countInboundEvents()).isZero();
        assertThat(invalid.body()).doesNotContain(signingSecret());
        assertThat(staleResponse.body()).doesNotContain(signingSecret());
        assertThat(tampered.body()).doesNotContain(signingSecret());
    }

    @Test
    void workspaceIdentityMustMatchTheIntegrationWhoseSecretVerifiedTheRequest() throws Exception {
        createSlackIntegration("T-OTHER");
        writeSigningSecret();
        String body = eventBody("Ev-wrong-team");
        String timestamp = currentTimestamp();

        HttpResponse<String> response = post(body, timestamp, sign(body, timestamp));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(countInboundEvents()).isZero();
    }

    @Test
    void validEventAckStaysBelowSlackThreeSecondLimitWithoutBusinessProcessing() throws Exception {
        createSlackIntegration(TEAM_ID);
        writeSigningSecret();
        String warmup = "{\"type\":\"url_verification\",\"team_id\":\"" + TEAM_ID
                + "\",\"challenge\":\"warmup\"}";
        String warmupTimestamp = currentTimestamp();
        assertThat(post(warmup, warmupTimestamp, sign(warmup, warmupTimestamp)).statusCode())
                .isEqualTo(200);

        String body = eventBody("Ev-latency");
        String timestamp = currentTimestamp();
        long started = System.nanoTime();
        HttpResponse<String> response = post(body, timestamp, sign(body, timestamp));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
        assertThat(countInboundEvents()).isEqualTo(1);
    }

    private static UUID createSlackIntegration(String workspaceExternalId) {
        return jdbc.queryForObject("""
                INSERT INTO integrations (
                    provider, display_name, status, health,
                    workspace_external_id, workspace_name, secret_ref, config_json
                )
                VALUES ('SLACK', 'Development Slack', 'CONFIGURING', 'UNKNOWN', ?, 'Development', ?, '{}'::jsonb)
                RETURNING id
                """, UUID.class, workspaceExternalId, SECRET_REF);
    }

    private static void writeSigningSecret() throws Exception {
        Path directory = secretRoot.resolve(SECRET_REF);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve(SlackRequestAuthenticator.SIGNING_CREDENTIAL_FILE),
                signingSecret() + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static void clearSecretRoot() throws Exception {
        try (var paths = Files.walk(secretRoot)) {
            paths.filter(path -> !path.equals(secretRoot))
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        }
    }

    private static int countInboundEvents() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM inbound_events", Integer.class);
        return count == null ? 0 : count;
    }

    private static String eventBody(String eventId) {
        return "{\"type\":\"event_callback\",\"team_id\":\"" + TEAM_ID
                + "\",\"event_id\":\"" + eventId
                + "\",\"event\":{\"type\":\"message\",\"text\":\"hello\"}}";
    }

    private static String currentTimestamp() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    private static HttpResponse<String> post(String body, String timestamp, String signature) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/v1/providers/slack/events"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Slack-Request-Timestamp", timestamp)
                .header("X-Slack-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String sign(String body, String timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String signingSecret() {
        return String.join("-", "test", "only", "slack", "signing", "credential");
    }
}
