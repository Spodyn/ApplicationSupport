package com.unifiedsupportinbox.provider.telegram.internal;

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
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
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
class TelegramWebhookIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final String SECRET_REF = "telegram/development-bot";
    private static final String BOT_ID = "telegram-development-bot";

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static URI baseUri;
    private static Path secretRoot;

    @BeforeAll
    static void startApplication() throws Exception {
        secretRoot = Files.createTempDirectory("usi-telegram-integration-secrets-");
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
        if (context != null) {
            context.close();
        }
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
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM inbound_events");
        jdbc.update("DELETE FROM channels");
        jdbc.update("DELETE FROM integrations");
        clearSecretRoot();
    }

    @Test
    void validWebhookIsDurableBeforeAcknowledgement() throws Exception {
        UUID integrationId = createTelegramIntegration();
        writeWebhookSecret();

        HttpResponse<String> response = post("{\"update_id\":12345,\"message\":{\"message_id\":1}}", webhookSecret());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(countInboundEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT integration_id FROM inbound_events WHERE external_event_id = '12345'", UUID.class))
                .isEqualTo(integrationId);
        assertThat(jdbc.queryForObject(
                        "SELECT provider FROM inbound_events WHERE external_event_id = '12345'", String.class))
                .isEqualTo("TELEGRAM");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM inbound_events WHERE external_event_id = '12345'", String.class))
                .isEqualTo("RECEIVED");
        assertThat(countOutboxEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM outbox_events", String.class))
                .isEqualTo(TelegramInboundDeliveryService.OUTBOX_TYPE);
    }

    @Test
    void duplicateUpdatesAreIdempotentAndReserveOnlyOneAsyncWake() throws Exception {
        createTelegramIntegration();
        writeWebhookSecret();
        String body = "{\"update_id\":34567,\"message\":{\"message_id\":1}}";

        HttpResponse<String> first = post(body, webhookSecret());
        HttpResponse<String> duplicate = post(body, webhookSecret());

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(countInboundEvents()).isEqualTo(1);
        assertThat(countOutboxEvents()).isEqualTo(1);
    }

    @Test
    void invalidSecretAndMalformedPayloadAreRejectedWithoutPersistence() throws Exception {
        createTelegramIntegration();
        writeWebhookSecret();

        HttpResponse<String> invalidSecret = post("{\"update_id\":45678}", "wrong-secret");
        HttpResponse<String> malformedPayload = post("not-json", webhookSecret());
        HttpResponse<String> missingUpdateId = post("{\"message\":{}}", webhookSecret());

        assertThat(invalidSecret.statusCode()).isEqualTo(401);
        assertThat(malformedPayload.statusCode()).isEqualTo(400);
        assertThat(missingUpdateId.statusCode()).isEqualTo(400);
        assertThat(countInboundEvents()).isZero();
        assertThat(countOutboxEvents()).isZero();
        assertThat(invalidSecret.body()).doesNotContain(webhookSecret());
    }

    @Test
    void acknowledgementStaysBelowTelegramRetryWindowWithoutBusinessProcessing() throws Exception {
        createTelegramIntegration();
        writeWebhookSecret();
        post("{\"update_id\":56788,\"message\":{\"message_id\":1}}", webhookSecret());

        long started = System.nanoTime();
        HttpResponse<String> response = post("{\"update_id\":56789,\"message\":{\"message_id\":2}}", webhookSecret());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
        assertThat(countInboundEvents()).isEqualTo(2);
    }

    private static UUID createTelegramIntegration() {
        return jdbc.queryForObject("""
                INSERT INTO integrations (
                    provider, display_name, status, health,
                    workspace_external_id, workspace_name, secret_ref, config_json
                )
                VALUES ('TELEGRAM', 'Development Telegram', 'CONFIGURING', 'UNKNOWN', ?, 'Development', ?, '{}'::jsonb)
                RETURNING id
                """, UUID.class, BOT_ID, SECRET_REF);
    }

    private static void writeWebhookSecret() throws Exception {
        Path directory = secretRoot.resolve(SECRET_REF);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve(TelegramWebhookAuthenticator.WEBHOOK_SECRET_FILE),
                webhookSecret() + System.lineSeparator(),
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

    private static int countOutboxEvents() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
        return count == null ? 0 : count;
    }

    private static HttpResponse<String> post(String body, String secretToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/v1/provider-callbacks/telegram"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String webhookSecret() {
        return String.join("-", "test", "only", "telegram", "webhook", "credential");
    }
}
