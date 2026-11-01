package com.unifiedsupportinbox.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
class UserStatisticsHttpIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "test-only-user-statistics-credential";
    private static ConfigurableApplicationContext context;
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
        jdbc.update("DELETE FROM analytics_daily_case_metrics");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void selfIsAllowedButAnotherUserAndGlobalAnalyticsAreDeniedEvenWithForgedGrant() throws Exception {
        UUID actor = user("agent@example.com", "USER", true);
        UUID other = user("other@example.com", "USER", true);
        jdbc.update("""
                INSERT INTO user_permissions (user_id, permission_code)
                VALUES (?, 'view_global_statistics')
                """, actor);
        HttpClient agent = client(login("agent@example.com"));

        HttpResponse<String> self = get(agent, "/api/v1/statistics/users/" + actor);
        assertThat(self.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(self.body()).get("metrics").get("userId").asString())
                .isEqualTo(actor.toString());
        assertThat(get(agent, "/api/v1/statistics/users/" + other).statusCode()).isEqualTo(403);
        assertThat(get(agent, "/api/v1/statistics/overview").statusCode()).isEqualTo(403);
    }

    @Test
    void adminCanReadInactiveHistoricalActorWithoutCurrentCaseOwnership() throws Exception {
        user("admin@example.com", "ADMIN", true);
        UUID former = user("former@example.com", "USER", false);
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO analytics_daily_case_metrics (
                    reporting_date, timezone_id, user_id, provider,
                    claimed_count, first_response_count, first_response_duration_seconds,
                    resolved_count, resolution_duration_seconds)
                VALUES (?, 'UTC', ?, 'SLACK', 2, 1, 40, 1, 80)
                """, day, former);
        HttpClient admin = client(login("admin@example.com"));
        String route = "/api/v1/statistics/users/" + former + "?from=" + day + "&to=" + day;
        HttpResponse<String> response = get(admin, route);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode metrics = JSON.readTree(response.body()).get("metrics");
        assertThat(metrics.get("active").asBoolean()).isFalse();
        assertThat(metrics.get("claimed").asLong()).isEqualTo(2);
        assertThat(metrics.get("averageFirstResponseSeconds").asLong()).isEqualTo(40);
        assertThat(metrics.get("averageResolutionSeconds").asLong()).isEqualTo(80);
        assertThat(response.body()).doesNotContain(PASSWORD, "passwordHash", "password_hash");
    }

    @Test
    void anonymousRequestNeverReturnsAUsersHistoricalStatistics() throws Exception {
        UUID target = user("agent@example.com", "USER", true);
        assertThat(get(client(new CookieManager(null, CookiePolicy.ACCEPT_ALL)),
                "/api/v1/statistics/users/" + target).statusCode()).isEqualTo(401);
    }

    private static UUID user(String email, String role, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, password_hash, role, active)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, email, email, encoder.encode(PASSWORD), role, active);
    }

    private static CookieManager login(String email) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = client(cookies);
        get(http, "/api/v1/auth/me");
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", cookies.getCookieStore().getCookies().stream()
                        .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                        .map(HttpCookie::getValue).findFirst().orElseThrow())
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        return cookies;
    }

    private static HttpClient client(CookieManager cookies) {
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private static HttpResponse<String> get(HttpClient http, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
