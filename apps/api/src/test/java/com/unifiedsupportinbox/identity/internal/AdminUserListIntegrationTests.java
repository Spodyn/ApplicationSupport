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
class AdminUserListIntegrationTests {

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
        jdbc.update("DELETE FROM users");
    }

    @Test
    void filtersRoleAndActiveWithoutLeakingCredentials() throws Exception {
        createUser("admin@example.com", UserRole.ADMIN, true);
        createUser("active@example.com", UserRole.USER, true);
        createUser("inactive@example.com", UserRole.USER, false);
        CookieManager cookies = login("admin@example.com");

        HttpResponse<String> response = get(client(cookies),
                "/api/v1/admin/users?role=USER&active=false");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode items = JSON.readTree(response.body()).get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("email").asString()).isEqualTo("inactive@example.com");
        assertThat(items.get(0).get("active").asBoolean()).isFalse();
        assertThat(response.body()).doesNotContain("passwordHash", "password_hash", "sessionId", "token");
    }

    @Test
    void searchAndCursorPaginationHaveNoDuplicateUsers() throws Exception {
        createUser("admin@example.com", UserRole.ADMIN, true);
        createUser("alice@example.com", UserRole.USER, true);
        createUser("bob@example.com", UserRole.USER, true);
        createUser("carol@example.com", UserRole.USER, true);
        CookieManager cookies = login("admin@example.com");
        HttpClient http = client(cookies);

        HttpResponse<String> first = get(http, "/api/v1/admin/users?search=example.com&limit=2");
        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode firstPage = JSON.readTree(first.body());
        assertThat(firstPage.get("items").size()).isEqualTo(2);
        String cursor = firstPage.get("nextCursor").asString();
        assertThat(cursor).isNotBlank();

        HttpResponse<String> second = get(http,
                "/api/v1/admin/users?search=example.com&limit=2&cursor=" + cursor);
        assertThat(second.statusCode()).isEqualTo(200);
        JsonNode secondPage = JSON.readTree(second.body());
        assertThat(secondPage.get("items").size()).isEqualTo(2);
        assertThat(secondPage.get("nextCursor").isNull()).isTrue();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JsonNode user : firstPage.get("items")) ids.add(user.get("id").asString());
        for (JsonNode user : secondPage.get("items")) ids.add(user.get("id").asString());
        assertThat(ids).hasSize(4);

        HttpResponse<String> wrongFilter = get(http,
                "/api/v1/admin/users?search=alice&limit=2&cursor=" + cursor);
        assertThat(wrongFilter.statusCode()).isEqualTo(400);
    }

    @Test
    void userWithManageUsersPermissionCannotReadAdminDirectory() throws Exception {
        createUser("admin@example.com", UserRole.ADMIN, true);
        UUID delegatedId = createUser("delegated@example.com", UserRole.USER, true);
        jdbc.update(
                "INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'manage_users')",
                delegatedId);
        CookieManager cookies = login("delegated@example.com");

        HttpResponse<String> response = get(client(cookies), "/api/v1/admin/users");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("ACCESS_DENIED");
    }

    @Test
    void anonymousDirectoryRequestRequiresAuthentication() throws Exception {
        HttpResponse<String> response = get(
                client(new CookieManager(null, CookiePolicy.ACCEPT_ALL)),
                "/api/v1/admin/users");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private static UUID createUser(String email, UserRole role, boolean active) {
        return users.saveAndFlush(new UserAccount(
                email, email, encoder.encode(testCredential()), role, active, null, null)).id();
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
        return "test-only-admin-user-directory-credential";
    }
}
