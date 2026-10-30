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
        jdbc.update("DELETE FROM cases");
        jdbc.update("DELETE FROM channels");
        jdbc.update("DELETE FROM integrations");
        jdbc.update("DELETE FROM customers");
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
    void presenceIsRecentSessionOnlyAndNeverOverridesAccountEligibility() throws Exception {
        createUser("admin@example.com", UserRole.ADMIN, true);
        UUID agent = createUser("agent@example.com", UserRole.USER, true);

        // Keep the agent's session separate from the administrator querying the
        // directory. There is no artificial sleep or reliance on test ordering.
        CookieManager agentCookies = login("agent@example.com");
        assertThat(get(client(agentCookies), "/api/v1/auth/me").statusCode()).isEqualTo(200);
        CookieManager adminCookies = login("admin@example.com");
        HttpClient admin = client(adminCookies);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM spring_session WHERE principal_name = ?",
                Integer.class, agent.toString())).isPositive();
        assertThat(presence(admin, "agent@example.com")).isEqualTo("online");

        jdbc.update("""
                UPDATE spring_session
                SET last_access_time = (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::bigint - 301000
                WHERE principal_name = ?
                """, agent.toString());
        assertThat(presence(admin, "agent@example.com")).isEqualTo("offline");

        jdbc.update("""
                UPDATE spring_session
                SET last_access_time = (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::bigint
                WHERE principal_name = ?
                """, agent.toString());
        jdbc.update("UPDATE users SET active = false WHERE id = ?", agent);
        assertThat(presence(admin, "agent@example.com")).isEqualTo("offline");

        jdbc.update("UPDATE users SET active = true, valid_from = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE id = ?", agent);
        assertThat(presence(admin, "agent@example.com")).isEqualTo("offline");

        jdbc.update("UPDATE users SET valid_from = NULL, valid_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", agent);
        assertThat(presence(admin, "agent@example.com")).isEqualTo("offline");
    }

    private static String presence(HttpClient admin, String email) throws Exception {
        HttpResponse<String> response = get(admin, "/api/v1/admin/users?search=" + email);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode items = JSON.readTree(response.body()).get("items");
        assertThat(items.size()).isEqualTo(1);
        return items.get(0).get("presence").asString();
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
    void countsOnlyNonTerminalCasesAssignedToEachUser() throws Exception {
        createUser("admin@example.com", UserRole.ADMIN, true);
        UUID agent = createUser("agent@example.com", UserRole.USER, true);
        UUID activeCase = newCase();
        UUID resolvedCase = newCase();
        jdbc.update("""
                UPDATE cases SET status = 'VERIFICATION', owner_user_id = ?, claimed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, agent, activeCase);
        jdbc.update("""
                UPDATE cases SET status = 'RESOLVED', owner_user_id = ?, claimed_at = CURRENT_TIMESTAMP,
                    resolved_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, agent, resolvedCase);

        HttpResponse<String> response = get(client(login("admin@example.com")),
                "/api/v1/admin/users?search=agent@example.com");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode item = JSON.readTree(response.body()).get("items").get(0);
        assertThat(item.get("activeAssignedCases").asLong()).isEqualTo(1L);
    }

    @Test
    void workloadTracksReassignmentAndTerminalTransitionWithoutPresenceCoupling() throws Exception {
        createUser("admin@example.com", UserRole.ADMIN, true);
        UUID first = createUser("first-agent@example.com", UserRole.USER, true);
        UUID second = createUser("second-agent@example.com", UserRole.USER, true);
        UUID caseId = newCase();

        jdbc.update("""
                UPDATE cases SET status = 'VERIFICATION', owner_user_id = ?,
                    claimed_at = CURRENT_TIMESTAMP WHERE id = ?
                """, first, caseId);
        HttpClient admin = client(login("admin@example.com"));
        assertThat(workload(admin, "first-agent@example.com")).isEqualTo(1);
        assertThat(workload(admin, "second-agent@example.com")).isZero();

        jdbc.update("UPDATE cases SET owner_user_id = ? WHERE id = ?", second, caseId);
        assertThat(workload(admin, "first-agent@example.com")).isZero();
        assertThat(workload(admin, "second-agent@example.com")).isEqualTo(1);

        jdbc.update("""
                UPDATE cases SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE id = ?
                """, caseId);
        assertThat(workload(admin, "second-agent@example.com")).isZero();
    }

    private static long workload(HttpClient admin, String email) throws Exception {
        HttpResponse<String> response = get(admin, "/api/v1/admin/users?search=" + email);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode items = JSON.readTree(response.body()).get("items");
        assertThat(items.size()).isEqualTo(1);
        return items.get(0).get("activeAssignedCases").asLong();
    }

    @Test
    void cursorCannotBeReusedByAnotherAdministrator() throws Exception {
        createUser("first-admin@example.com", UserRole.ADMIN, true);
        createUser("second-admin@example.com", UserRole.ADMIN, true);
        createUser("agent@example.com", UserRole.USER, true);

        HttpResponse<String> first = get(client(login("first-admin@example.com")),
                "/api/v1/admin/users?limit=1");
        assertThat(first.statusCode()).isEqualTo(200);
        String cursor = JSON.readTree(first.body()).get("nextCursor").asString();

        HttpResponse<String> reused = get(client(login("second-admin@example.com")),
                "/api/v1/admin/users?limit=1&cursor=" + cursor);
        assertThat(reused.statusCode()).isEqualTo(400);
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

    private static UUID newCase() {
        String external = UUID.randomUUID().toString();
        UUID customer = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class, "Customer " + external, external);
        UUID integration = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', 'Slack', 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, external);
        UUID channel = jdbc.queryForObject("""
                INSERT INTO channels (integration_id, external_channel_id, name, customer_id, ignored, grouping_strategy, active)
                VALUES (?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE) RETURNING id
                """, UUID.class, integration, external, customer);
        return jdbc.queryForObject("""
                INSERT INTO cases (customer_id, integration_id, channel_id, provider, external_conversation_id)
                VALUES (?, ?, ?, 'SLACK', ?) RETURNING id
                """, UUID.class, customer, integration, channel, external);
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
