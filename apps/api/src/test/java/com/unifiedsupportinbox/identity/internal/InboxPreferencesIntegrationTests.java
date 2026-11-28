package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
class InboxPreferencesIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static ConfigurableApplicationContext context;
    private static UserAccountRepository users;
    private static JdbcTemplate jdbc;
    private static PasswordEncoder encoder;
    private static URI baseUri;

    @BeforeAll
    static void startApplication() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class).profiles("test").run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                "--spring.flyway.enabled=true",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.session.jdbc.initialize-schema=never",
                "--usi.bootstrap-admin.enabled=false");
        users = context.getBean(UserAccountRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
        encoder = context.getBean("bootstrapAdminPasswordEncoder", PasswordEncoder.class);
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
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM cases");
        jdbc.update("DELETE FROM channels");
        jdbc.update("DELETE FROM integrations");
        jdbc.update("DELETE FROM customers");
        jdbc.update("DELETE FROM users");
    }

    private static final String PATH = "/api/v1/users/me/inbox-preferences";

    @Test
    void ordinaryAccountsPersistAcrossSessionsAndCannotWriteAnotherAccountsPreference() throws Exception {
        UUID first = createUser("first@example.com", UserRole.USER, true);
        UUID second = createUser("second@example.com", UserRole.USER, true);
        CookieManager firstCookies = login("first@example.com");
        CookieManager secondCookies = login("second@example.com");
        assertThat(get(client(firstCookies), PATH).body()).contains("UNIFIED");
        assertThat(save(firstCookies, "{\"mode\":\"SEPARATE_BY_PLATFORM\",\"userId\":\"" + second + "\"}", true).statusCode()).isEqualTo(200);
        assertThat(get(client(login("first@example.com")), PATH).body()).contains("SEPARATE_BY_PLATFORM");
        assertThat(get(client(secondCookies), PATH).body()).contains("UNIFIED");
        assertThat(jdbc.queryForObject("SELECT user_id FROM user_inbox_preferences", UUID.class)).isEqualTo(first);
        assertThat(get(client(firstCookies), "/api/v1/admin/settings").statusCode()).isEqualTo(403);
        assertThat(save(firstCookies, "{\"mode\":\"UNIFIED\"}", true).statusCode()).isEqualTo(200);
        assertThat(get(client(login("first@example.com")), PATH).body()).contains("UNIFIED");
    }

    @Test
    void rejectsUnauthenticatedCsrfAndInvalidPreferencesWithoutMutation() throws Exception {
        assertThat(get(client(new CookieManager()), PATH).statusCode()).isEqualTo(401);
        createUser("user@example.com", UserRole.USER, true);
        CookieManager cookies = login("user@example.com");
        assertThat(save(cookies, "{\"mode\":\"SEPARATE_BY_PLATFORM\"}", false).statusCode()).isEqualTo(403);
        assertThat(save(cookies, "{}", true).statusCode()).isEqualTo(400);
        assertThat(save(cookies, "{\"mode\":\"INVALID\"}", true).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_inbox_preferences", Integer.class)).isZero();
    }

    @Test
    void writesOnlyChangedPreferencesToTheTransactionalPersonalOutbox() throws Exception {
        UUID user = createUser("owner@example.com", UserRole.USER, true);
        CookieManager cookies = login("owner@example.com");
        assertThat(save(cookies, "{\"mode\":\"SEPARATE_BY_PLATFORM\"}", true).statusCode()).isEqualTo(200);
        assertThat(save(cookies, "{\"mode\":\"SEPARATE_BY_PLATFORM\"}", true).statusCode()).isEqualTo(200);
        assertThat(hints(user)).isEqualTo(1);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE user_inbox_preferences SET mode = 'UNIFIED' WHERE user_id = ?", user);
            status.setRollbackOnly();
        });
        assertThat(hints(user)).isEqualTo(1);
        assertThat(get(client(cookies), PATH).body()).contains("SEPARATE_BY_PLATFORM");
        assertThat(save(cookies, "{\"mode\":\"UNIFIED\"}", true).statusCode()).isEqualTo(200);
        assertThat(hints(user)).isEqualTo(2);
    }

    @Test
    void platformCatalogIncludesConfiguredDisabledAndEmptyIntegrationsWithoutAdminData() throws Exception {
        createUser("catalog@example.com", UserRole.USER, true);
        CookieManager cookies = login("catalog@example.com");
        assertThat(JSON.readTree(get(client(cookies), "/api/v1/cases/filter-options").body()).path("providers").size()).isZero();
        jdbc.update("INSERT INTO integrations(provider, display_name, status, health, config_json) VALUES ('TEAMS', 'Secret admin label', 'DISABLED', 'HEALTHY', '{}'::jsonb)");
        jdbc.update("INSERT INTO integrations(provider, display_name, status, health, config_json) VALUES ('SLACK', 'Another label', 'ENABLED', 'HEALTHY', '{}'::jsonb)");
        var response = get(client(cookies), "/api/v1/cases/filter-options");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("providers").toString()).isEqualTo("[\"SLACK\",\"TEAMS\"]");
        assertThat(response.body()).doesNotContain("Secret admin label", "Another label", "config_json");
    }

    private int hints(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE type = 'application.preferences_changed' AND payload_json->>'userId' = ?", Integer.class, user.toString());
    }

    private static HttpResponse<String> save(CookieManager cookies, String body, boolean withCsrf) throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve(PATH)).header("Content-Type", "application/json");
        if (withCsrf) request.header("X-XSRF-TOKEN", cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue());
        return client(cookies).send(request.PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static UUID createUser(String email, UserRole role, boolean active) {
        UUID userId = users.saveAndFlush(new UserAccount(
                email, email, encoder.encode(testCredential()), role, active, null, null)).id();
        if (role == UserRole.ADMIN) {
            jdbc.update("INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'manage_users')", userId);
        }
        return userId;
    }

    private static CookieManager login(String email) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = client(cookies);
        get(http, "/api/v1/auth/me");
        String csrf = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .findFirst().orElseThrow().getValue();
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + testCredential() + "\"}"))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        return cookies;
    }

    private static HttpClient client(CookieManager cookies) {
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private static HttpResponse<String> get(HttpClient http, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String testCredential() {
        return "test-only-inbox-preferences-credential";
    }
}
