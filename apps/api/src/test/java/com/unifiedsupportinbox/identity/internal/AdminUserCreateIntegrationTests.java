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

/** Database-backed tests of the actual authenticated admin HTTP command. */
@Tag("integration")
class AdminUserCreateIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "test-only-admin-create-credential";
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
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void createsUserWithArgon2PasswordAndAuditWithoutSecrets() throws Exception {
        user("admin@example.com", UserRole.ADMIN);
        CookieManager cookies = login("admin@example.com");
        String body = body("Agent@Example.com", "USER", "[]", PASSWORD);
        HttpResponse<String> response = post(client(cookies), cookies, body, "create-agent-1");
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("agent@example.com", "PASSWORD_SET");
        assertThat(response.body()).doesNotContain(PASSWORD, "passwordHash", "password_hash");
        UserAccount created = users.findByEmailIgnoreCase("agent@example.com").orElseThrow();
        assertThat(encoder.matches(PASSWORD, created.passwordHash())).isTrue();
        assertThat(created.passwordHash()).isNotEqualTo(PASSWORD);
        CookieManager newAccountCookies = login("agent@example.com");
        HttpResponse<String> loggedIn = get(client(newAccountCookies), "/api/v1/auth/me");
        assertThat(loggedIn.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(loggedIn.body()).path("role").asString()).isEqualTo("USER");
        String audit = jdbc.queryForObject(
                "SELECT metadata_json::text FROM audit_events WHERE action = 'USER_CREATED'",
                String.class);
        assertThat(audit).contains("INITIAL_PASSWORD").doesNotContain(PASSWORD, created.passwordHash());
    }

    @Test
    void sameKeyReplaysExactlyOneUserAndDifferentPayloadConflicts() throws Exception {
        user("admin@example.com", UserRole.ADMIN);
        CookieManager cookies = login("admin@example.com");
        HttpClient http = client(cookies);
        String payload = body("new@example.com", "USER", "[]", PASSWORD);
        HttpResponse<String> first = post(http, cookies, payload, "same-create-key");
        HttpResponse<String> replay = post(http, cookies, payload, "same-create-key");
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(replay.body()).path("id").asString())
                .isEqualTo(JSON.readTree(first.body()).path("id").asString());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = 'new@example.com'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE action = 'USER_CREATED'", Integer.class)).isEqualTo(1);
        HttpResponse<String> mismatch = post(http, cookies,
                body("different@example.com", "USER", "[]", PASSWORD), "same-create-key");
        assertThat(mismatch.statusCode()).isEqualTo(409);
    }

    @Test
    void rejectsDuplicateAndInvalidPasswordWithoutCreatingAccount() throws Exception {
        user("admin@example.com", UserRole.ADMIN);
        CookieManager cookies = login("admin@example.com");
        HttpClient http = client(cookies);
        assertThat(post(http, cookies, body("first@example.com", "USER", "[]", PASSWORD),
                "create-first").statusCode()).isEqualTo(201);
        assertThat(post(http, cookies, body("first@example.com", "USER", "[]", PASSWORD),
                "duplicate-first").statusCode()).isEqualTo(409);
        assertThat(post(http, cookies, body("short@example.com", "USER", "[]", "short"),
                "short-password").statusCode()).isEqualTo(400);
        assertThat(post(http, cookies,
                "{\"email\":\"missing@example.com\",\"fullName\":\"Missing\",\"role\":\"USER\",\"active\":true,\"permissions\":[]}",
                "missing-password").statusCode()).isEqualTo(400);
        assertThat(users.findByEmailIgnoreCase("short@example.com")).isEmpty();
        assertThat(users.findByEmailIgnoreCase("missing@example.com")).isEmpty();
    }

    @Test
    void userCannotCreateAccountsEvenWithManageUsersPermission() throws Exception {
        user("admin@example.com", UserRole.ADMIN);
        UUID userId = user("delegated@example.com", UserRole.USER);
        jdbc.update("INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'manage_users')", userId);
        CookieManager cookies = login("delegated@example.com");
        assertThat(post(client(cookies), cookies, body("forbidden@example.com", "USER", "[]", PASSWORD),
                "user-not-admin").statusCode()).isEqualTo(403);
        assertThat(users.findByEmailIgnoreCase("forbidden@example.com")).isEmpty();
    }

    @Test
    void userGrantEscalationAndPartialAdministratorGrantsAreRejected() throws Exception {
        user("admin@example.com", UserRole.ADMIN);
        CookieManager cookies = login("admin@example.com");
        HttpClient http = client(cookies);
        assertThat(post(http, cookies, body("bad@example.com", "USER", "[\"manage_users\"]", PASSWORD),
                "user-grants").statusCode()).isEqualTo(400);
        assertThat(post(http, cookies, body("limited@example.com", "ADMIN", "[\"manage_users\"]", PASSWORD),
                "partial-admin").statusCode()).isEqualTo(400);
        HttpResponse<String> valid = post(http, cookies,
                body("second-admin@example.com", "ADMIN", "null", PASSWORD), "full-admin");
        assertThat(valid.statusCode()).isEqualTo(201);
        UUID id = users.findByEmailIgnoreCase("second-admin@example.com").orElseThrow().id();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_permissions WHERE user_id = ?", Integer.class, id))
                .isEqualTo(PermissionCatalog.ALL.size());
    }

    private static UUID user(String email, UserRole role) {
        return users.saveAndFlush(new UserAccount(
                email, email, encoder.encode(PASSWORD), role, true, null, null)).id();
    }

    private static CookieManager login(String email) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = client(cookies);
        get(http, "/api/v1/auth/me");
        String csrf = csrf(cookies);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        return cookies;
    }

    private static String csrf(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue).findFirst().orElseThrow();
    }

    private static HttpResponse<String> post(
            HttpClient http, CookieManager cookies, String body, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(baseUri.resolve("/api/v1/admin/users"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf(cookies))
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String body(String email, String role, String grants, String password) {
        return "{\"email\":\"" + email + "\",\"fullName\":\"Test Agent\",\"role\":\"" +
                role + "\",\"active\":true,\"permissions\":" + grants +
                ",\"initialPassword\":\"" + password + "\"}";
    }

    private static HttpResponse<String> get(HttpClient http, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient client(CookieManager cookies) {
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }
}
