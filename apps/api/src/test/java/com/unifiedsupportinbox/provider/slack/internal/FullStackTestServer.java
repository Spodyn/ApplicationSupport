package com.unifiedsupportinbox.provider.slack.internal;

import com.sun.net.httpserver.HttpServer;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.storage.AttachmentMalwareScanner;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

/** Test classpath only. Owns fresh containers; never reads a developer .env or provider account. */
public final class FullStackTestServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Map<String, Object>> DELIVERED = new CopyOnWriteArrayList<>();
    private static URI providerUri;
    private static JdbcTemplate fixtureDatabase;
    private static PasswordEncoder fixtureEncoder;
    private static UUID fixtureIntegration;
    private static final List<String> PERMISSIONS = List.of("manage_users", "manage_integrations", "manage_sla", "manage_schedule",
            "manage_notifications", "view_global_statistics", "reassign_cases", "force_resolve", "view_audit");

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("http://127\\.0\\.0\\.1:[0-9]+")) {
            throw new IllegalArgumentException("Expected metadata path and loopback browser origin");
        }
        var postgres = TestInfrastructure.postgres();
        var rabbit = TestInfrastructure.rabbitMq();
        var minio = TestInfrastructure.minio();
        var secrets = Files.createTempDirectory("usi-fullstack-secrets-");
        Files.createDirectories(secrets.resolve("test-only"));
        Files.writeString(secrets.resolve("test-only/slack-bot-token"), "test-only-fullstack-bot");
        Files.writeString(secrets.resolve("test-only/slack-signing-secret"), "test-only-fullstack-signing");
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/", exchange -> {
            Map<String, ?> response;
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/account") && fixtureDatabase != null) {
                String suffix = UUID.randomUUID().toString().replace("-", "");
                String email = "fixture-" + suffix + "@example.test";
                UUID user = fixtureDatabase.queryForObject("""
                        INSERT INTO users (email, display_name, password_hash, role, active)
                        VALUES (?, 'Regression Admin', ?, 'ADMIN', TRUE) RETURNING id
                        """, UUID.class, email, fixtureEncoder.encode("test-only-fullstack-credential"));
                for (String permission : PERMISSIONS) fixtureDatabase.update("INSERT INTO user_permissions (user_id, permission_code) VALUES (?, ?)", user, permission);
                String externalChannel = "CTEST" + suffix.toUpperCase();
                UUID channel = fixtureDatabase.queryForObject("""
                        INSERT INTO channels (integration_id, external_channel_id, name, grouping_strategy)
                        VALUES (?, ?, 'synthetic-support', 'SLACK_ROOT_THREAD') RETURNING id
                        """, UUID.class, fixtureIntegration, externalChannel);
                response = Map.of("email", email, "userId", user, "channelId", channel,
                        "externalChannelId", externalChannel, "workspaceId", "TTESTFULL");
            } else if (path.equals("/observed")) response = Map.of("messages", DELIVERED);
            else if (path.equals("/chat.postMessage")) {
                var body = JSON.readTree(exchange.getRequestBody());
                if (!"Bearer test-only-fullstack-bot".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    response = Map.of("ok", false, "error", "invalid_auth");
                } else if (body.path("text").asText().contains("test-only-archived")) {
                    response = Map.of("ok", false, "error", "is_archived");
                } else {
                    String ts = "1790000000." + String.format("%06d", DELIVERED.size() + 1);
                    DELIVERED.add(Map.of("clientMessageId", body.path("client_msg_id").asText(),
                            "thread", body.path("thread_ts").asText(), "ts", ts));
                    response = Map.of("ok", true, "ts", ts);
                }
            } else if (path.equals("/auth.test")) response = Map.of("ok", true, "team_id", "TTESTFULL", "team", "Synthetic workspace");
            else if (path.equals("/conversations.list")) response = Map.of("ok", true, "channels", List.of(Map.of(
                    "id", "CTESTFULL", "name", "synthetic-support", "is_member", true, "is_private", false)),
                    "response_metadata", Map.of("next_cursor", ""));
            else response = Map.of("ok", false, "error", "test_endpoint_not_implemented");
            byte[] bytes = JSON.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            if (path.equals("/shutdown") && exchange.getRequestMethod().equals("POST")) {
                new Thread(() -> System.exit(0), "test-only-cleanup").start();
            }
        });
        provider.start();
        providerUri = URI.create("http://127.0.0.1:" + provider.getAddress().getPort());
        try {
            postgres.start(); rabbit.start(); minio.start();
            List<String> options = new ArrayList<>(List.of(
                    "--spring.config.location=classpath:/application.properties,classpath:/application-test.properties",
                    "--server.address=127.0.0.1", "--server.port=0", "--usi.public-base-url=" + args[1],
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.datasource.driver-class-name=" + postgres.getDriverClassName(),
                    "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
                    "--spring.rabbitmq.host=" + rabbit.getHost(), "--spring.rabbitmq.port=" + rabbit.getAmqpPort(),
                    "--spring.rabbitmq.username=" + rabbit.getAdminUsername(), "--spring.rabbitmq.password=" + rabbit.getAdminPassword(),
                    "--spring.rabbitmq.virtual-host=/", "--management.health.rabbit.enabled=true",
                    "--management.otlp.metrics.export.enabled=false", "--spring.session.jdbc.cleanup-cron=-",
                    "--usi.integration-secrets.backend=filesystem", "--usi.integration-secrets.directory=" + secrets,
                    "--usi.object-storage.endpoint=http://" + minio.getHost() + ":" + minio.getMappedPort(9000),
                    "--usi.object-storage.access-key=usi-test-access", "--usi.object-storage.secret-key=usi-test-secret-key",
                    "--usi.providers.slack.outbound.enabled=true", "--usi.outbox.relay-enabled=true", "--usi.outbox.poll-interval=100ms",
                    "--usi.slack.inbound-worker.enabled=true", "--usi.slack.inbound-worker.poll-interval=100ms",
                    "--usi.messages.delivery-worker.enabled=true", "--usi.messages.delivery-worker.poll-interval=100ms",
                    "--usi.realtime.case-lifecycle-worker.enabled=true", "--usi.realtime.message-created-worker.enabled=true",
                    "--usi.readstate.case-created-worker.enabled=true", "--usi.realtime.heartbeat=1s"));
            var context = new SpringApplicationBuilder(UsiApiApplication.class, ProviderBoundary.class)
                    .profiles("test").run(options.toArray(String[]::new));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                context.close(); provider.stop(0); minio.stop(); rabbit.stop(); postgres.stop();
                try (var files = Files.walk(secrets)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> { try { Files.deleteIfExists(file); } catch (Exception ignored) { } });
                } catch (Exception ignored) { }
            }));
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String password = "test-only-fullstack-credential";
            UUID administrator = jdbc.queryForObject("""
                    INSERT INTO users (email, display_name, password_hash, role, active)
                    VALUES (?, ?, ?, 'ADMIN', TRUE) RETURNING id
                    """, UUID.class, "admin@example.test", "Regression Admin",
                    context.getBean(PasswordEncoder.class).encode(password));
            for (String permission : List.of("manage_users", "manage_integrations", "manage_sla", "manage_schedule",
                    "manage_notifications", "view_global_statistics", "reassign_cases", "force_resolve", "view_audit")) {
                jdbc.update("INSERT INTO user_permissions (user_id, permission_code) VALUES (?, ?)", administrator, permission);
            }
            UUID integration = jdbc.queryForObject("""
                    INSERT INTO integrations (provider, display_name, status, health, workspace_external_id, secret_ref, config_json)
                    VALUES ('SLACK', 'Synthetic Slack', 'ENABLED', 'HEALTHY', 'TTESTFULL', 'test-only', '{}'::jsonb) RETURNING id
                    """, UUID.class);
            UUID channel = jdbc.queryForObject("""
                    INSERT INTO channels (integration_id, external_channel_id, name, grouping_strategy)
                    VALUES (?, 'CTESTFULL', 'synthetic-support', 'SLACK_ROOT_THREAD') RETURNING id
                    """, UUID.class, integration);
            fixtureDatabase = jdbc;
            fixtureEncoder = context.getBean(PasswordEncoder.class);
            fixtureIntegration = integration;
            var s3 = context.getBean(S3Client.class);
            s3.createBucket(request -> request.bucket("usi-attachments-test"));
            var metadata = Map.of("backend", "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"),
                    "provider", providerUri.toString(), "adminId", administrator.toString(), "integrationId", integration.toString(),
                    "channelId", channel.toString());
            Path destination = Path.of(args[0]);
            Files.writeString(destination, JSON.writeValueAsString(metadata), StandardCharsets.UTF_8);
            new CountDownLatch(1).await();
        } catch (Throwable failure) {
            provider.stop(0); minio.stop(); rabbit.stop(); postgres.stop();
            throw failure;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderBoundary {
        @Bean @Primary SlackWebApiClient syntheticSlackHttp(ObjectMapper mapper) {
            return new HttpSlackWebApiClient(HttpClient.newHttpClient(), mapper, providerUri.resolve("/chat.postMessage"));
        }
        @Bean @Primary SlackAdministrationApiClient syntheticAdministrationHttp(ObjectMapper mapper) {
            return new HttpSlackAdministrationApiClient(HttpClient.newHttpClient(), mapper,
                    providerUri.resolve("/auth.test"), providerUri.resolve("/conversations.list"));
        }
        @Bean @Primary AttachmentMalwareScanner deterministicScanner() {
            return content -> {
                try {
                    return new String(content.readNBytes(25 * 1024 * 1024 + 1), StandardCharsets.UTF_8)
                            .contains("test-only-infected") ? AttachmentMalwareScanner.ScanResult.INFECTED : AttachmentMalwareScanner.ScanResult.CLEAN;
                } catch (java.io.IOException failure) {
                    throw new com.unifiedsupportinbox.storage.AttachmentScanException(
                            com.unifiedsupportinbox.storage.AttachmentScanException.Code.SCANNER_IO_ERROR, failure);
                }
            };
        }
    }
}
