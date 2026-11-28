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
    private static final List<Map<String,Object>> MUTATED = new CopyOnWriteArrayList<>();
    private static com.unifiedsupportinbox.provider.teams.internal.ControlledTeamsConnector teamsConnector;
    private static final Map<String,String> SLACK_CONTENT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<Map<String, Object>> SHARED_FILES = new CopyOnWriteArrayList<>();
    private static final java.util.Set<String> UPLOADED_FILES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> FAILED_SCANS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> THROTTLED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static URI providerUri;
    private static JdbcTemplate fixtureDatabase;
    private static PasswordEncoder fixtureEncoder;
    private static UUID fixtureIntegration;
    private static com.unifiedsupportinbox.channel.ChannelDiscovery fixtureDiscovery;
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
        Files.writeString(secrets.resolve("test-only/teams-client-secret"), "test-only-fullstack-teams");
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/", exchange -> {
            Map<String, ?> response;
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            if ((path.equals("/account") || path.equals("/agent") || path.equals("/private-account")) && fixtureDatabase != null) {
                String suffix = UUID.randomUUID().toString().replace("-", "");
                String email = "fixture-" + suffix + "@example.test";
                String role = path.equals("/agent") ? "USER" : "ADMIN";
                String name = "Regression " + role + " " + suffix.substring(0, 8);
                UUID user = fixtureDatabase.queryForObject("""
                        INSERT INTO users (email, display_name, password_hash, role, active)
                        VALUES (?, ?, ?, ?, TRUE) RETURNING id
                        """, UUID.class, email, name, fixtureEncoder.encode("test-only-fullstack-credential"), role);
                if (role.equals("ADMIN")) {
                    for (String permission : PERMISSIONS) fixtureDatabase.update(
                            "INSERT INTO user_permissions (user_id, permission_code) VALUES (?, ?)", user, permission);
                }
                String externalChannel = (path.equals("/private-account") ? "GTEST" : "CTEST") + suffix.toUpperCase();
                UUID channel = fixtureDatabase.queryForObject("""
                        INSERT INTO channels (integration_id, external_channel_id, name, grouping_strategy)
                        VALUES (?, ?, 'synthetic-support', 'SLACK_ROOT_THREAD') RETURNING id
                        """, UUID.class, fixtureIntegration, externalChannel);
                response = Map.of("email", email, "displayName", name, "userId", user, "channelId", channel,
                        "externalChannelId", externalChannel, "workspaceId", "TTESTFULL");
            } else if (path.equals("/teams-mutation-fixture") && exchange.getRequestMethod().equals("POST")) {
                String marker=JSON.readTree(exchange.getRequestBody()).path("marker").asText();
                String external="19:"+UUID.randomUUID(),root="root-"+UUID.randomUUID();
                UUID customer=fixtureDatabase.queryForObject("INSERT INTO customers(name) VALUES (?) RETURNING id",UUID.class,marker);
                UUID integration=fixtureDatabase.queryForObject("INSERT INTO integrations(provider,display_name,status,health,workspace_external_id,secret_ref) VALUES ('TEAMS',?,'ENABLED','HEALTHY',?,'test-only') RETURNING id",UUID.class,marker,com.unifiedsupportinbox.provider.teams.internal.ControlledTeamsMutations.TENANT);
                UUID channel=fixtureDatabase.queryForObject("INSERT INTO channels(integration_id,external_channel_id,name,customer_id,grouping_strategy,metadata_json) VALUES (?,?,?,?,'TEAMS_ROOT_REPLIES','{\"teamsContextType\":\"standardChannel\"}'::jsonb) RETURNING id",UUID.class,integration,external,marker,customer);
                fixtureDatabase.update("INSERT INTO teams_conversation_routes(integration_id,external_channel_id,thread_key,connector_conversation_id,service_url,bot_id,context_type) VALUES (?,?,?,?,'https://smba.trafficmanager.net/emea/',?,'standardChannel')",integration,external,root,external+";messageid="+root,"28:"+com.unifiedsupportinbox.provider.teams.internal.ControlledTeamsMutations.BOT);
                UUID caseId=fixtureDatabase.queryForObject("INSERT INTO cases(customer_id,integration_id,channel_id,provider,external_conversation_id,external_thread_key) VALUES (?,?,?,'TEAMS',?,?) RETURNING id",UUID.class,customer,integration,channel,external,root);
                fixtureDatabase.update("INSERT INTO messages(case_id,external_message_id,external_thread_key,kind,author_external_id,author_name,body,inbound,provider_created_at,correlation_id) VALUES (?,?,?,'CUSTOMER','fixture','Test customer',?,TRUE,now(),'teams-mutation-fixture')",caseId,root,root,marker);
                response=Map.of("caseId",caseId,"channel",external,"conversation",external+";messageid="+root);
            } else if (path.equals("/teams-mutation-fixture") && exchange.getRequestMethod().equals("DELETE")) {
                UUID caseId=UUID.fromString(JSON.readTree(exchange.getRequestBody()).path("caseId").asText());
                var owned=fixtureDatabase.queryForMap("SELECT integration_id,channel_id,customer_id FROM cases c WHERE c.id=? AND c.provider='TEAMS' AND c.status='RESOLVED' AND EXISTS (SELECT 1 FROM messages m WHERE m.case_id=c.id AND m.correlation_id='teams-mutation-fixture')",caseId);
                // Retain the exercised Case and immutable audit until this disposable stack is destroyed.
                fixtureDatabase.update("UPDATE channels SET active=false WHERE id=?",owned.get("channel_id"));
                fixtureDatabase.update("UPDATE integrations SET status='DISABLED' WHERE id=?",owned.get("integration_id"));
                response=Map.of("cleaned",true,"retainedAudit",true);
            } else if (path.equals("/inbox-platform-fixture") && exchange.getRequestMethod().equals("POST")) {
                // Inert Teams fixture: no provider credentials, selected-team binding or external calls.
                String marker = JSON.readTree(exchange.getRequestBody()).path("marker").asText();
                String external = UUID.randomUUID().toString();
                UUID customer = fixtureDatabase.queryForObject("INSERT INTO customers(name) VALUES (?) RETURNING id", UUID.class, marker + " customer");
                UUID integration = fixtureDatabase.queryForObject("INSERT INTO integrations(provider,display_name,status,health) VALUES ('TEAMS',?,'DISABLED','HEALTHY') RETURNING id", UUID.class, marker);
                UUID channel = fixtureDatabase.queryForObject("INSERT INTO channels(integration_id,external_channel_id,name,customer_id,grouping_strategy) VALUES (?,?,?,?,'TEAMS_ROOT_REPLIES') RETURNING id", UUID.class, integration, external, marker + " teams", customer);
                UUID caseId = fixtureDatabase.queryForObject("INSERT INTO cases(customer_id,integration_id,channel_id,provider,external_conversation_id) VALUES (?,?,?,'TEAMS',?) RETURNING id", UUID.class, customer, integration, channel, external);
                fixtureDatabase.update("INSERT INTO messages(case_id,external_message_id,kind,author_external_id,author_name,body,inbound,provider_created_at,correlation_id) VALUES (?,?,'CUSTOMER','fixture','Test customer',?,TRUE,now(),'inbox-fixture')", caseId, external, marker + " teams");
                response = Map.of("caseId", caseId);
            } else if (path.equals("/inbox-platform-fixture") && exchange.getRequestMethod().equals("DELETE")) {
                UUID caseId = UUID.fromString(JSON.readTree(exchange.getRequestBody()).path("caseId").asText());
                var owned = fixtureDatabase.queryForMap("""
                        SELECT integration_id, channel_id, customer_id FROM cases c
                        WHERE c.id=? AND c.provider='TEAMS' AND EXISTS
                          (SELECT 1 FROM messages m WHERE m.case_id=c.id AND m.correlation_id='inbox-fixture')
                        """, caseId);
                fixtureDatabase.update("DELETE FROM cases WHERE id=?", caseId);
                fixtureDatabase.update("DELETE FROM channels WHERE id=?", owned.get("channel_id"));
                fixtureDatabase.update("DELETE FROM integrations WHERE id=?", owned.get("integration_id"));
                fixtureDatabase.update("DELETE FROM customers WHERE id=?", owned.get("customer_id"));
                response = Map.of("cleaned", true);
            } else if (path.equals("/rename-channel") && exchange.getRequestMethod().equals("POST")) {
                var input = JSON.readTree(exchange.getRequestBody());
                UUID channelId = UUID.fromString(input.path("channelId").asText());
                var channel = fixtureDatabase.queryForMap("SELECT integration_id, external_channel_id, grouping_strategy FROM channels WHERE id = ?", channelId);
                var renamed = fixtureDiscovery.upsert(new com.unifiedsupportinbox.channel.DiscoveredChannel(
                        (UUID) channel.get("integration_id"), (String) channel.get("external_channel_id"),
                        input.path("name").asText(), com.unifiedsupportinbox.channel.ChannelGroupingStrategy.valueOf((String) channel.get("grouping_strategy")),
                        true, null, "{}"));
                response = Map.of("id", renamed.id(), "name", renamed.name());
            } else if (path.equals("/expire-deadline") && exchange.getRequestMethod().equals("POST")) {
                var input = JSON.readTree(exchange.getRequestBody());
                UUID caseId = UUID.fromString(input.path("caseId").asText());
                if (input.path("closeBusinessCalendar").asBoolean(false)) {
                    // Per-fixture closures only. Never change the shared policy or server clock.
                    fixtureDatabase.update("""
                            UPDATE case_operational_deadlines SET schedule = jsonb_build_object(
                              'id', id, 'timezone', 'UTC', 'active', true,
                              'intervals', jsonb_build_array(jsonb_build_object('dayOfWeek', 1, 'start', '09:00', 'end', '17:00')),
                              'exceptions', jsonb_build_array(
                                jsonb_build_object('date', (now() AT TIME ZONE 'UTC')::date, 'type', 'CLOSED'),
                                jsonb_build_object('date', (now() AT TIME ZONE 'UTC')::date + 1, 'type', 'CLOSED')),
                              'updatedBy', 'test', 'updatedAt', now())
                            WHERE case_id=? AND ended_at IS NULL AND kind IN ('FRT','NRT')
                            """, caseId);
                }
                response = Map.of("changed", expireDeadline(caseId));
            } else if (path.equals("/breach") && exchange.getRequestMethod().equals("POST")) {
                UUID caseId = UUID.fromString(JSON.readTree(exchange.getRequestBody()).path("caseId").asText());
                int changed = fixtureDatabase.update("""
                        UPDATE case_sla SET state = 'BREACHED',
                            first_response_breached_at = first_response_due_at
                        WHERE case_id = ?
                        """, caseId);
                fixtureDatabase.update("""
                        UPDATE case_next_response_cycles
                        SET started_at = now() - interval '40 minutes',
                            warning_at = now() - interval '17 minutes 30 seconds',
                            due_at = now() - interval '10 minutes'
                        WHERE case_id = ? AND ended_at IS NULL
                        """, caseId);
                expireDeadline(caseId);
                response = Map.of("changed", changed);
            } else if (path.equals("/observed")) response = Map.of("messages", DELIVERED, "files", SHARED_FILES,"mutations",MUTATED,"teamsMutations",teamsConnector.mutations(),"slackContent",SLACK_CONTENT,"teamsActivities",teamsConnector.activities());
            else if (path.equals("/files.getUploadURLExternal")) {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!"application/x-www-form-urlencoded".equals(exchange.getRequestHeaders().getFirst("Content-Type"))
                        || !form.matches("filename=[^&]+&length=[0-9]+")) {
                    response = Map.of("ok", false, "error", "invalid_arguments");
                } else {
                    String filename = java.net.URLDecoder.decode(form.substring("filename=".length(), form.indexOf('&')), StandardCharsets.UTF_8);
                    if (filename.startsWith("scope-required-")) {
                        response = Map.of("ok", false, "error", "missing_scope", "needed", "files:write");
                    } else {
                        String fileId = "F" + UUID.randomUUID().toString().replace("-", "");
                        response = Map.of("ok", true, "file_id", fileId, "upload_url", providerUri.resolve("/bytes/" + fileId).toString());
                    }
                }
            } else if (path.startsWith("/bytes/")) {
                if (exchange.getRequestBody().readAllBytes().length > 0) UPLOADED_FILES.add(path.substring("/bytes/".length()));
                response = Map.of("ok", true);
            } else if (path.equals("/files.info")) {
                String id = exchange.getRequestURI().getQuery().substring("file=".length());
                var shared = SHARED_FILES.stream().filter(file -> id.equals(file.get("id"))).findFirst();
                if (id.startsWith("F-inbound-")) {
                    if (id.startsWith("F-inbound-missing-scope-")
                            || !"Bearer test-only-fullstack-bot".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        response = Map.of("ok", false, "error", "missing_scope", "needed", "files:read");
                    } else {
                        response = Map.of("ok", true, "file", Map.of("id", id, "name", id + "." + fixtureExtension(id),
                                "mimetype", fixtureType(id), "size", inboundFileBytes(id).length,
                                "url_private_download", providerUri.resolve("/download/" + id).toString()));
                    }
                } else response = shared.<Map<String, ?>>map(file -> Map.of("ok", true, "file", Map.of(
                        "id", id, "shares", Map.of("public", Map.of(file.get("channel"),
                                List.of(Map.of("thread_ts", file.get("thread")))))))).orElseGet(
                                        () -> Map.of("ok", false, "error", "file_not_found"));
            } else if (path.startsWith("/download/")) {
                String id = path.substring("/download/".length());
                boolean allowed = "Bearer test-only-fullstack-bot".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        && !id.startsWith("F-inbound-denied-");
                byte[] payload = allowed ? inboundFileBytes(id) : new byte[0];
                exchange.getResponseHeaders().set("Content-Type", fixtureType(id));
                exchange.sendResponseHeaders(allowed ? 200 : 403, payload.length);
                try (var output = exchange.getResponseBody()) { output.write(payload); }
                return;
            } else if (path.equals("/files.completeUploadExternal")) {
                var body = JSON.readTree(exchange.getRequestBody());
                var file = body.path("files").get(0);
                String id = file.path("id").asText();
                String title = file.path("title").asText();
                if (!UPLOADED_FILES.contains(id)) {
                    response = Map.of("ok", false, "error", "file_not_found");
                } else if (title.startsWith("retry-") && THROTTLED.add(title)) {
                    status = 429;
                    exchange.getResponseHeaders().add("Retry-After", "1");
                    response = Map.of("ok", false, "error", "ratelimited");
                } else if (SHARED_FILES.stream().anyMatch(shared -> id.equals(shared.get("id")))) {
                    response = Map.of("ok", false, "error", "file_update_failed");
                } else {
                    SHARED_FILES.add(Map.of("id", id, "title", title,
                            "channel", body.path("channel_id").asText(), "thread", body.path("thread_ts").asText()));
                    if (title.startsWith("lost-response-")) {
                        status = 503;
                        response = Map.of("ok", false, "error", "service_unavailable");
                    } else {
                        response = Map.of("ok", true, "files", List.of(Map.of("id", id)));
                    }
                }
            } else if (path.equals("/chat.update") || path.equals("/chat.delete")) {
                var input=JSON.readTree(exchange.getRequestBody());String ts=input.path("ts").asText(),channel=input.path("channel").asText();
                boolean own=DELIVERED.stream().anyMatch(m->ts.equals(m.get("ts"))&&channel.equals(m.get("channel")));
                if(!"Bearer test-only-fullstack-bot".equals(exchange.getRequestHeaders().getFirst("Authorization")))response=Map.of("ok",false,"error","invalid_auth");
                else if(!own||!SLACK_CONTENT.containsKey(ts))response=Map.of("ok",false,"error","message_not_found");
                else {
                    if(path.equals("/chat.update"))SLACK_CONTENT.put(ts,input.path("text").asText());else SLACK_CONTENT.remove(ts);
                    MUTATED.add(Map.of("method",path,"ts",ts,"channel",channel,"text",input.path("text").asText()));
                    response=Map.of("ok",true,"channel",channel,"ts",ts);
                }
            } else if (path.equals("/chat.postMessage")) {
                var body = JSON.readTree(exchange.getRequestBody());
                if (!"Bearer test-only-fullstack-bot".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    response = Map.of("ok", false, "error", "invalid_auth");
                } else if (body.path("text").asText().isBlank()) {
                    response = Map.of("ok", false, "error", "no_text");
                } else if (body.path("text").asText().contains("test-only-archived")) {
                    status = 403;
                    response = Map.of("ok", false, "error", "is_archived");
                } else if (body.path("text").asText().contains("test-only-rate-limit")
                        && THROTTLED.add(body.path("client_msg_id").asText())) {
                    status = 429;
                    exchange.getResponseHeaders().add("Retry-After", "4");
                    response = Map.of("ok", false, "error", "ratelimited");
                } else {
                    String ts = "1790000000." + String.format("%06d", DELIVERED.size() + 1);
                    DELIVERED.add(Map.of("clientMessageId", body.path("client_msg_id").asText(),
                            "thread", body.path("thread_ts").asText(), "channel", body.path("channel").asText(), "ts", ts));
                    SLACK_CONTENT.put(ts,body.path("text").asText());
                    response = Map.of("ok", true, "ts", ts,"message",Map.of("bot_id","BTESTFULL"));
                }
            } else if (path.equals("/auth.test")) response = Map.of("ok", true, "team_id", "TTESTFULL", "team", "Synthetic workspace");
            else if (path.equals("/conversations.list")) response = Map.of("ok", true, "channels", List.of(Map.of(
                    "id", "CTESTFULL", "name", "synthetic-support", "is_member", true, "is_private", false)),
                    "response_metadata", Map.of("next_cursor", ""));
            else response = Map.of("ok", false, "error", "test_endpoint_not_implemented");
            byte[] bytes = JSON.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
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
                    // This suite runs simultaneous browser sessions and durable workers.
                    // Match the prod pool rather than the four-connection unit-test profile.
                    "--spring.datasource.hikari.maximum-pool-size=20",
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
                    "--usi.cases.waiting-timeout.poll-interval=1s",
                    "--usi.realtime.case-lifecycle-worker.enabled=true", "--usi.realtime.message-created-worker.enabled=true",
                    "--usi.readstate.case-created-worker.enabled=true", "--usi.realtime.heartbeat=1s"));
            var context = new SpringApplicationBuilder(UsiApiApplication.class, ProviderBoundary.class, com.unifiedsupportinbox.provider.teams.internal.ControlledTeamsConnector.Configuration.class, com.unifiedsupportinbox.provider.teams.internal.ControlledTeamsMutations.class)
                    .profiles("test").run(options.toArray(String[]::new));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                context.close(); provider.stop(0); minio.stop(); rabbit.stop(); postgres.stop();
                try (var files = Files.walk(secrets)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> { try { Files.deleteIfExists(file); } catch (Exception ignored) { } });
                } catch (Exception ignored) { }
            }));
            teamsConnector=context.getBean(com.unifiedsupportinbox.provider.teams.internal.ControlledTeamsConnector.class);
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
            fixtureDiscovery = context.getBean(com.unifiedsupportinbox.channel.ChannelDiscovery.class);
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

    private static int expireDeadline(UUID caseId) {
        return fixtureDatabase.update("""
                UPDATE case_operational_deadlines
                SET started_at = LEAST(started_at, now() - interval '2 minutes'),
                    warning_at = CASE WHEN warning_at IS NULL THEN NULL ELSE now() - interval '2 minutes' END,
                    due_at = now() - interval '1 minute'
                WHERE case_id = ? AND ended_at IS NULL
                """, caseId);
    }

    private static final Map<String, String> FIXTURE_MIME_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"), Map.entry("avif", "image/avif"), Map.entry("bmp", "image/bmp"),
            Map.entry("tiff", "image/tiff"), Map.entry("pdf", "application/pdf"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"));

    private static String fixtureExtension(String id) {
        if (!id.startsWith("F-inbound-format-")) return "txt";
        String extension = id.substring(id.lastIndexOf('-') + 1);
        if (!FIXTURE_MIME_TYPES.containsKey(extension)) throw new IllegalArgumentException("Unknown fixture type");
        return extension;
    }

    private static String fixtureType(String id) {
        return FIXTURE_MIME_TYPES.getOrDefault(fixtureExtension(id), "text/plain");
    }

    private static byte[] inboundFileBytes(String id) throws java.io.IOException {
        if (id.startsWith("F-inbound-format-")) {
            try (var file = FullStackTestServer.class.getResourceAsStream("/attachments/preview-fixtures/sample." + fixtureExtension(id))) {
                return java.util.Objects.requireNonNull(file).readAllBytes();
            }
        }
        return (id.startsWith("F-inbound-infected-") ? "test-only-infected" :
                id.startsWith("F-inbound-scan-error-") ? "test-only-scanner-offline-" + id : "Synthetic inbound " + id)
                .getBytes(StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderBoundary {
        @Bean @Primary SlackWebApiClient syntheticSlackHttp(ObjectMapper mapper) {
            return new HttpSlackWebApiClient(HttpClient.newHttpClient(), mapper, providerUri.resolve("/chat.postMessage"));
        }
        @Bean @Primary SlackAttachmentApiClient syntheticSlackFiles(ObjectMapper mapper) {
            return new HttpSlackAttachmentApiClient(HttpClient.newHttpClient(), mapper,
                    providerUri.resolve("/files.info"), providerUri.resolve("/files.getUploadURLExternal"),
                    providerUri.resolve("/files.completeUploadExternal")) {
                @Override
                boolean safeSlackUploadUri(URI uri) {
                    return uri != null && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                            && uri.getPort() == providerUri.getPort() && uri.getPath().startsWith("/bytes/");
                }
            };
        }
        @Bean @Primary SlackProviderAttachmentGateway syntheticSlackFileGateway(
                com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup integrations,
                com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver secrets,
                SlackAttachmentApiClient slack) {
            return new SlackProviderAttachmentGateway(integrations, secrets, slack, (provider, uri, token) -> {
                if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                        || providerUri.getPort() != uri.getPort() || !uri.getPath().startsWith("/download/")) {
                    throw new IllegalArgumentException("Synthetic download must target the loopback provider.");
                }
                try {
                    var request = java.net.http.HttpRequest.newBuilder(uri)
                            .header("Authorization", "Bearer " + new String(token, StandardCharsets.US_ASCII)).GET().build();
                    var response = HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() != 200) throw new IllegalStateException("Synthetic download was denied.");
                    return new com.unifiedsupportinbox.provider.SafeProviderFileDownloader.DownloadedFile(
                            response.body(), "text/plain");
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException(failure);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            });
        }
        @Bean @Primary SlackAdministrationApiClient syntheticAdministrationHttp(ObjectMapper mapper) {
            return new HttpSlackAdministrationApiClient(HttpClient.newHttpClient(), mapper,
                    providerUri.resolve("/auth.test"), providerUri.resolve("/conversations.list"));
        }
        @Bean @Primary AttachmentMalwareScanner deterministicScanner() {
            return content -> {
                try {
                    String value = new String(content.readNBytes(25 * 1024 * 1024 + 1), StandardCharsets.UTF_8);
                    if (value.startsWith("test-only-scanner-offline-") && FAILED_SCANS.add(value)) {
                        throw new com.unifiedsupportinbox.storage.AttachmentScanException(
                                com.unifiedsupportinbox.storage.AttachmentScanException.Code.SCANNER_UNAVAILABLE);
                    }
                    return value.contains("test-only-infected") ? AttachmentMalwareScanner.ScanResult.INFECTED : AttachmentMalwareScanner.ScanResult.CLEAN;
                } catch (java.io.IOException failure) {
                    throw new com.unifiedsupportinbox.storage.AttachmentScanException(
                            com.unifiedsupportinbox.storage.AttachmentScanException.Code.SCANNER_IO_ERROR, failure);
                }
            };
        }
    }
}
