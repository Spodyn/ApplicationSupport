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
import java.time.Instant;
import java.util.List;
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

@Tag("integration")
class PermissionIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static UserAccountRepository users;
    private static PermissionService permissions;
    private static PasswordEncoder encoder;
    private static JdbcTemplate jdbc;
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
        users = context.getBean(UserAccountRepository.class);
        permissions = context.getBean(PermissionService.class);
        encoder = context.getBean("bootstrapAdminPasswordEncoder", PasswordEncoder.class);
        jdbc = context.getBean(JdbcTemplate.class);
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
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("UPDATE bootstrap_admin_state SET consumed = FALSE, consumed_at = NULL, admin_user_id = NULL WHERE id = 1");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void catalogIsSeededAndAdminBaselineContainsEveryFrozenPermission() {
        assertThat(jdbc.queryForList("SELECT code FROM permissions ORDER BY code", String.class))
                .containsExactlyInAnyOrderElementsOf(PermissionCatalog.ALL);

        UserAccount admin = createUser("admin@example.com", UserRole.ADMIN, true);
        assertThat(permissions.effectivePermissions(admin.id()))
                .containsExactlyElementsOf(PermissionCatalog.ALL);
    }

    @Test
    void adminCanAddAndRemoveExplicitPermissionsAndMeReadsCurrentDatabaseState() throws Exception {
        UserAccount admin = createUser("admin@example.com", UserRole.ADMIN, true);
        UserAccount user = createUser("user@example.com", UserRole.USER, true);

        CookieManager adminCookies = login("admin@example.com");
        HttpResponse<String> added = putPermissions(
                adminCookies,
                user.id(),
                "[\"manage_notifications\",\"view_audit\"]");
        assertThat(added.statusCode()).isEqualTo(200);
        assertThat(added.body())
                .contains("\"explicitPermissions\":[\"manage_notifications\",\"view_audit\"]")
                .contains("\"effectivePermissions\":[\"manage_notifications\",\"view_audit\"]");

        CookieManager userCookies = login("user@example.com");
        assertThat(get(client(userCookies), "/api/v1/auth/me").body())
                .contains("\"effectivePermissions\":[\"manage_notifications\",\"view_audit\"]");

        HttpResponse<String> removed = putPermissions(adminCookies, user.id(), "[\"view_audit\"]");
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(get(client(userCookies), "/api/v1/auth/me").body())
                .contains("\"effectivePermissions\":[\"view_audit\"]")
                .doesNotContain("manage_notifications");

        assertThat(admin.id()).isNotNull();
    }

    @Test
    void ordinaryUserCannotSelfEscalateAndInactiveUserHasNoEffectivePermissions() throws Exception {
        UserAccount user = createUser("user@example.com", UserRole.USER, true);
        CookieManager cookies = login("user@example.com");

        HttpResponse<String> denied = putPermissions(cookies, user.id(), "[\"manage_users\"]");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_permissions WHERE user_id = ?",
                Integer.class,
                user.id())).isZero();

        jdbc.update(
                "INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'view_audit')",
                user.id());
        UserAccount currentUser = users.findById(user.id()).orElseThrow();
        currentUser.setActive(false);
        users.saveAndFlush(currentUser);
        assertThat(permissions.effectivePermissions(user.id())).isEmpty();
    }

    private static UserAccount createUser(String email, UserRole role, boolean active) {
        return users.saveAndFlush(new UserAccount(
                email,
                email,
                encoder.encode(testCredential()),
                role,
                active,
                null,
                null));
    }

    private static CookieManager login(String email) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = client(cookies);
        primeCsrf(http, cookies);
        HttpResponse<String> response = postJson(
                http,
                "/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + testCredential() + "\"}",
                csrfToken(cookies));
        assertThat(response.statusCode()).isEqualTo(200);
        return cookies;
    }

    private static HttpResponse<String> putPermissions(
            CookieManager cookies,
            UUID userId,
            String permissionsJson) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/v1/users/" + userId + "/permissions"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken(cookies))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"permissions\":" + permissionsJson + "}"))
                .build();
        return client(cookies).send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void primeCsrf(HttpClient http, CookieManager cookies) throws Exception {
        HttpResponse<String> response = get(http, "/api/v1/auth/me");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(csrfToken(cookies)).isNotBlank();
    }

    private static String csrfToken(CookieManager cookies) {
        return cookieValue(cookies, "XSRF-TOKEN");
    }

    private static String cookieValue(CookieManager cookies, String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> name.equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private static HttpClient client(CookieManager cookies) {
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(
            HttpClient client,
            String path,
            String body,
            String csrfToken) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", csrfToken)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String testCredential() {
        return String.join("-", "test", "only", "permission", "credential");
    }
}
