package com.unifiedsupportinbox.channel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.channel.ChannelDiscovery;
import com.unifiedsupportinbox.channel.ChannelGroupingStrategy;
import com.unifiedsupportinbox.channel.ChannelView;
import com.unifiedsupportinbox.channel.DiscoveredChannel;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class ChannelIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static PasswordEncoder encoder;
    private static ChannelDiscovery discovery;
    private static URI baseUri;

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
                        "--usi.bootstrap-admin.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        encoder = context.getBean("bootstrapAdminPasswordEncoder", PasswordEncoder.class);
        discovery = context.getBean(ChannelDiscovery.class);
        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        baseUri = URI.create("http://127.0.0.1:" + port);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM channels");
        jdbc.update("DELETE FROM integrations");
        jdbc.update("DELETE FROM customers");
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("UPDATE bootstrap_admin_state SET consumed = FALSE, consumed_at = NULL, admin_user_id = NULL WHERE id = 1");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void discoveryUpsertRenamesExistingChannelAndPreservesAdminMapping() {
        UUID integrationId = createIntegration("TELEGRAM", "Support Telegram");
        UUID customerId = createCustomer("Acme");
        Instant firstMessage = Instant.parse("2026-08-29T05:00:00Z");

        ChannelView created = discovery.upsert(new DiscoveredChannel(
                integrationId,
                "chat-42",
                "Old name",
                ChannelGroupingStrategy.TELEGRAM_TOPIC,
                true,
                firstMessage,
                "{\"kind\":\"forum\"}"));

        jdbc.update("""
                UPDATE channels
                SET customer_id = ?,
                    ignored = TRUE,
                    grouping_strategy = 'TELEGRAM_CHAT_ACTIVE_CASE'
                WHERE id = ?
                """, customerId, created.id());

        Instant newerMessage = Instant.parse("2026-08-29T06:00:00Z");
        ChannelView renamed = discovery.upsert(new DiscoveredChannel(
                integrationId,
                "chat-42",
                "Renamed by provider",
                ChannelGroupingStrategy.TELEGRAM_TOPIC,
                false,
                newerMessage,
                "{\"kind\":\"renamed\"}"));

        assertThat(renamed.id()).isEqualTo(created.id());
        assertThat(renamed.name()).isEqualTo("Renamed by provider");
        assertThat(renamed.active()).isFalse();
        assertThat(renamed.lastMessageAt()).isEqualTo(newerMessage);
        assertThat(renamed.customerId()).isEqualTo(customerId);
        assertThat(renamed.customerName()).isEqualTo("Acme");
        assertThat(renamed.ignored()).isTrue();
        assertThat(renamed.groupingStrategy()).isEqualTo(ChannelGroupingStrategy.TELEGRAM_CHAT_ACTIVE_CASE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channels", Integer.class)).isEqualTo(1);
    }

    @Test
    void uniqueProviderChannelIdentityAndCustomerForeignKeyAreEnforced() {
        UUID integrationId = createIntegration("SLACK", "Support Slack");
        ChannelView channel = discovery.upsert(new DiscoveredChannel(
                integrationId,
                "C123",
                "support",
                ChannelGroupingStrategy.SLACK_ROOT_THREAD,
                true,
                null,
                "{}"));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, grouping_strategy, metadata_json
                ) VALUES (?, 'C123', 'duplicate', 'SLACK_ROOT_THREAD', '{}'::jsonb)
                """, integrationId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE channels SET customer_id = ? WHERE id = ?",
                UUID.randomUUID(),
                channel.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void discoveryDoesNotRegressLastMessageTimestamp() {
        UUID integrationId = createIntegration("TEAMS", "Support Teams");
        Instant newest = Instant.parse("2026-08-29T07:00:00Z");
        Instant stale = Instant.parse("2026-08-29T06:00:00Z");

        discovery.upsert(new DiscoveredChannel(
                integrationId,
                "19:channel",
                "General",
                ChannelGroupingStrategy.TEAMS_ROOT_REPLIES,
                true,
                newest,
                "{}"));
        ChannelView afterStaleSync = discovery.upsert(new DiscoveredChannel(
                integrationId,
                "19:channel",
                "General",
                ChannelGroupingStrategy.TEAMS_ROOT_REPLIES,
                true,
                stale,
                "{}"));

        assertThat(afterStaleSync.lastMessageAt()).isEqualTo(newest);
    }

    @Test
    void adminCanListAndUpdateIgnoredStateWithManageIntegrations() throws Exception {
        UUID integrationId = createIntegration("SLACK", "Support Slack");
        ChannelView channel = discovery.upsert(new DiscoveredChannel(
                integrationId,
                "C789",
                "triage",
                ChannelGroupingStrategy.SLACK_ROOT_THREAD,
                true,
                Instant.parse("2026-08-29T06:30:00Z"),
                "{}"));

        UUID delegatedId = createUser("delegated@example.com", "USER");
        jdbc.update(
                "INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'manage_integrations')",
                delegatedId);
        CookieManager delegated = login("delegated@example.com");

        HttpResponse<String> listed = get(client(delegated), "/api/v1/admin/channels");
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body())
                .contains("\"externalChannelId\":\"C789\"")
                .contains("\"provider\":\"SLACK\"")
                .contains("\"active\":true")
                .contains("\"ignored\":false")
                .doesNotContain("metadataJson");

        HttpResponse<String> updated = mutate(
                delegated,
                "PATCH",
                "/api/v1/admin/channels/" + channel.id(),
                "{\"ignored\":true}");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body())
                .contains("\"ignored\":true")
                .contains("\"active\":true");
    }

    @Test
    void adminCanChangeCustomerAndGroupingWithoutRewritingExistingCases() throws Exception {
        UUID integrationId = createIntegration("SLACK", "Support Slack");
        UUID originalCustomer = createCustomer("Original Customer");
        UUID targetCustomer = createCustomer("Target Customer");
        ChannelView channel = discovery.upsert(new DiscoveredChannel(
                integrationId,
                "C-map",
                "mapping",
                ChannelGroupingStrategy.SLACK_ROOT_THREAD,
                true,
                null,
                "{}"));
        jdbc.update("UPDATE channels SET customer_id = ? WHERE id = ?", originalCustomer, channel.id());
        UUID caseId = jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider, external_conversation_id
                ) VALUES (?, ?, ?, 'SLACK', 'historic-thread') RETURNING id
                """, UUID.class, originalCustomer, integrationId, channel.id());

        UUID delegatedId = createUser("mapping-admin@example.com", "USER");
        jdbc.update(
                "INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'manage_integrations')",
                delegatedId);
        CookieManager delegated = login("mapping-admin@example.com");

        HttpResponse<String> updated = mutate(
                delegated,
                "PATCH",
                "/api/v1/admin/channels/" + channel.id(),
                "{\"customerId\":\"" + targetCustomer + "\",\"groupingStrategy\":\"SLACK_ROOT_THREAD\"}");

        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body())
                .contains("\"customerId\":\"" + targetCustomer + "\"")
                .contains("\"customerName\":\"Target Customer\"")
                .contains("\"groupingStrategy\":\"SLACK_ROOT_THREAD\"");
        assertThat(jdbc.queryForObject(
                "SELECT customer_id FROM cases WHERE id = ?",
                UUID.class,
                caseId)).isEqualTo(originalCustomer);
    }

    @Test
    void channelConfigurationRejectsInactiveCustomerAndInvalidProviderStrategy() throws Exception {
        UUID integrationId = createIntegration("SLACK", "Support Slack");
        UUID activeCustomer = createCustomer("Active Customer");
        UUID inactiveCustomer = jdbc.queryForObject(
                "INSERT INTO customers (name, active) VALUES ('Inactive Customer', FALSE) RETURNING id",
                UUID.class);
        ChannelView channel = discovery.upsert(new DiscoveredChannel(
                integrationId,
                "C-invalid",
                "invalid",
                ChannelGroupingStrategy.SLACK_ROOT_THREAD,
                true,
                null,
                "{}"));
        createUser("admin-channel-config@example.com", "ADMIN");
        CookieManager admin = login("admin-channel-config@example.com");

        HttpResponse<String> inactive = mutate(
                admin,
                "PATCH",
                "/api/v1/admin/channels/" + channel.id(),
                "{\"customerId\":\"" + inactiveCustomer + "\",\"groupingStrategy\":\"SLACK_ROOT_THREAD\"}");
        assertThat(inactive.statusCode()).isEqualTo(400);
        assertThat(inactive.body()).contains("\"code\":\"VALIDATION_FAILED\"");

        HttpResponse<String> invalidStrategy = mutate(
                admin,
                "PATCH",
                "/api/v1/admin/channels/" + channel.id(),
                "{\"customerId\":\"" + activeCustomer + "\",\"groupingStrategy\":\"TEAMS_ROOT_REPLIES\"}");
        assertThat(invalidStrategy.statusCode()).isEqualTo(400);
        assertThat(invalidStrategy.body()).contains("\"code\":\"VALIDATION_FAILED\"");
    }

    @Test
    void channelAdminRoutesRejectMissingPermission() throws Exception {
        UUID integrationId = createIntegration("SLACK", "Support Slack");
        discovery.upsert(new DiscoveredChannel(
                integrationId,
                "C999",
                "support",
                ChannelGroupingStrategy.SLACK_ROOT_THREAD,
                true,
                null,
                "{}"));

        CookieManager anonymous = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        primeCsrf(client(anonymous), anonymous);
        assertThat(get(client(anonymous), "/api/v1/admin/channels").statusCode()).isEqualTo(401);

        createUser("plain@example.com", "USER");
        CookieManager plain = login("plain@example.com");
        HttpResponse<String> denied = get(client(plain), "/api/v1/admin/channels");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("\"code\":\"ACCESS_DENIED\"");
    }

    private static UUID createIntegration(String provider, String name) {
        return jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, config_json)
                VALUES (?, ?, 'ENABLED', 'HEALTHY', '{}'::jsonb)
                RETURNING id
                """, UUID.class, provider, name);
    }

    private static UUID createCustomer(String name) {
        return jdbc.queryForObject(
                "INSERT INTO customers (name, active) VALUES (?, TRUE) RETURNING id",
                UUID.class,
                name);
    }

    private static UUID createUser(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, display_name, password_hash, role, active) VALUES (?, ?, ?, ?, TRUE) RETURNING id",
                UUID.class,
                email,
                email,
                encoder.encode(testCredential()),
                role);
    }

    private static CookieManager login(String email) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = client(cookies);
        primeCsrf(http, cookies);
        HttpResponse<String> response = mutate(
                cookies,
                "POST",
                "/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + testCredential() + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return cookies;
    }

    private static void primeCsrf(HttpClient http, CookieManager cookies) throws Exception {
        HttpResponse<String> response = get(http, "/api/v1/auth/me");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(csrfToken(cookies)).isNotBlank();
    }

    private static HttpResponse<String> mutate(
            CookieManager cookies,
            String method,
            String path,
            String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Accept", "application/json")
                .header("X-XSRF-TOKEN", csrfToken(cookies));
        if (body != null) builder.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        builder.method(method, publisher);
        return client(cookies).send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String csrfToken(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse("");
    }

    private static HttpClient client(CookieManager cookies) {
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String testCredential() {
        return String.join("-", "test", "only", "channel", "credential");
    }
}
