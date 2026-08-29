package com.unifiedsupportinbox.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.integration.IntegrationHealth;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.IntegrationStatus;
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
class IntegrationIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static PasswordEncoder encoder;
    private static IntegrationRepository integrations;
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
        integrations = context.getBean(IntegrationRepository.class);
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
        jdbc.update("DELETE FROM integrations");
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("UPDATE bootstrap_admin_state SET consumed = FALSE, consumed_at = NULL, admin_user_id = NULL WHERE id = 1");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void adminListAndDetailExposeCommonModelWithoutSecretMaterial() throws Exception {
        UUID integrationId = integrations.create(
                IntegrationProvider.SLACK,
                "Acme Slack",
                IntegrationStatus.ENABLED,
                IntegrationHealth.HEALTHY,
                "T12345",
                "Acme Workspace",
                "vault://integrations/slack/acme",
                "{\"scope\":\"support\"}")
                .id();

        createUser("admin@example.com", "ADMIN");
        CookieManager cookies = login("admin@example.com");

        HttpResponse<String> listed = get(client(cookies), "/api/v1/admin/integrations");
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body())
                .contains("\"provider\":\"SLACK\"")
                .contains("\"status\":\"ENABLED\"")
                .contains("\"health\":\"HEALTHY\"")
                .contains("\"workspaceExternalId\":\"T12345\"")
                .contains("\"secretConfigured\":true")
                .doesNotContain("vault://integrations/slack/acme")
                .doesNotContain("secretRef")
                .doesNotContain("configJson");

        HttpResponse<String> detail = get(client(cookies), "/api/v1/admin/integrations/" + integrationId);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body())
                .contains("\"id\":\"" + integrationId + "\"")
                .contains("\"workspaceName\":\"Acme Workspace\"")
                .doesNotContain("vault://integrations/slack/acme");
    }

    @Test
    void activeWorkspaceMappingIsUniqueAndProviderIsValidated() {
        integrations.create(
                IntegrationProvider.TEAMS,
                "Primary Teams",
                IntegrationStatus.ENABLED,
                IntegrationHealth.UNKNOWN,
                "tenant-42",
                "Acme Tenant",
                null,
                "{}");

        assertThatThrownBy(() -> integrations.create(
                IntegrationProvider.TEAMS,
                "Duplicate Teams",
                IntegrationStatus.CONFIGURING,
                IntegrationHealth.UNKNOWN,
                "tenant-42",
                "Duplicate",
                null,
                "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);

        IntegrationRecord disabled = integrations.create(
                IntegrationProvider.TEAMS,
                "Historical Teams",
                IntegrationStatus.DISABLED,
                IntegrationHealth.UNAVAILABLE,
                "tenant-42",
                "Historical",
                null,
                "{}");
        assertThat(disabled.status()).isEqualTo(IntegrationStatus.DISABLED);
        assertThatThrownBy(() -> integrations.updateStatus(disabled.id(), IntegrationStatus.ENABLED))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO integrations (provider, display_name, status, health, config_json)
                VALUES ('EMAIL', 'Unsupported', 'CONFIGURING', 'UNKNOWN', '{}'::jsonb)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusAndHealthTransitionsStayIndependent() {
        IntegrationRecord created = integrations.create(
                IntegrationProvider.TELEGRAM,
                "Support Bot",
                IntegrationStatus.CONFIGURING,
                IntegrationHealth.UNKNOWN,
                "bot-123",
                "@support_bot",
                "config://telegram/support",
                "{}");

        IntegrationRecord enabled = integrations.updateStatus(created.id(), IntegrationStatus.ENABLED);
        assertThat(enabled.status()).isEqualTo(IntegrationStatus.ENABLED);
        assertThat(enabled.health()).isEqualTo(IntegrationHealth.UNKNOWN);

        Instant eventAt = Instant.parse("2026-08-29T05:00:00Z");
        IntegrationRecord healthy = integrations.updateHealth(
                created.id(), IntegrationHealth.HEALTHY, eventAt, null);
        assertThat(healthy.status()).isEqualTo(IntegrationStatus.ENABLED);
        assertThat(healthy.health()).isEqualTo(IntegrationHealth.HEALTHY);
        assertThat(healthy.lastEventAt()).isEqualTo(eventAt);

        IntegrationRecord disabled = integrations.updateStatus(created.id(), IntegrationStatus.DISABLED);
        assertThat(disabled.health()).isEqualTo(IntegrationHealth.HEALTHY);
    }

    @Test
    void integrationReadRoutesRequireManageIntegrationsPermission() throws Exception {
        integrations.create(
                IntegrationProvider.SLACK,
                "Acme Slack",
                IntegrationStatus.ENABLED,
                IntegrationHealth.HEALTHY,
                "T123",
                "Acme",
                null,
                "{}");

        CookieManager anonymous = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        primeCsrf(client(anonymous), anonymous);
        assertThat(get(client(anonymous), "/api/v1/admin/integrations").statusCode()).isEqualTo(401);

        createUser("plain-user@example.com", "USER");
        CookieManager plainUser = login("plain-user@example.com");
        HttpResponse<String> denied = get(client(plainUser), "/api/v1/admin/integrations");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("\"code\":\"ACCESS_DENIED\"");

        UUID delegatedUserId = createUser("delegated@example.com", "USER");
        jdbc.update(
                "INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'manage_integrations')",
                delegatedUserId);
        CookieManager delegated = login("delegated@example.com");
        assertThat(get(client(delegated), "/api/v1/admin/integrations").statusCode()).isEqualTo(200);

        createUser("admin@example.com", "ADMIN");
        CookieManager admin = login("admin@example.com");
        assertThat(get(client(admin), "/api/v1/admin/integrations").statusCode()).isEqualTo(200);
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
        HttpResponse<String> response = mutate(cookies, "POST", "/api/v1/auth/login",
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
        return String.join("-", "test", "only", "integration", "credential");
    }
}
