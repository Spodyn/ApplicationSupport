package com.unifiedsupportinbox.sla.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class BusinessHoursIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static PasswordEncoder encoder;
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
        jdbc.update("DELETE FROM business_hour_intervals");
        jdbc.update("DELETE FROM business_hours");
        resetDefaultBusinessHours();
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update(
                "UPDATE bootstrap_admin_state "
                        + "SET consumed = FALSE, consumed_at = NULL, admin_user_id = NULL WHERE id = 1");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void bootstrapScheduleUsesFrozenUtcWeekdayDefaultsAndSingleActiveSchedule() {
        assertThat(queryInt("SELECT count(*) FROM business_hours WHERE active = TRUE")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT timezone_id FROM business_hours WHERE active = TRUE",
                        String.class))
                .isEqualTo("UTC");
        assertThat(queryInt("SELECT count(*) FROM business_hour_intervals")).isEqualTo(5);
        assertThat(queryInt("""
                SELECT count(*)
                FROM business_hour_intervals
                WHERE day_of_week BETWEEN 1 AND 5
                  AND start_time = TIME '09:00'
                  AND end_time = TIME '17:00'
                """))
                .isEqualTo(5);
        assertThat(queryInt("SELECT count(*) FROM business_hour_intervals WHERE day_of_week IN (6, 7)"))
                .isZero();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO business_hours (timezone_id, active, updated_by)
                VALUES ('UTC', TRUE, 'second-active')
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void manageScheduleCanReadAndReplaceWeeklyIntervalsAndAttribution() throws Exception {
        UUID delegatedId = createUser("schedule-admin@example.com");
        jdbc.update(
                "INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'manage_schedule')",
                delegatedId);
        CookieManager session = login("schedule-admin@example.com");

        HttpResponse<String> initial = get(client(session), "/api/v1/admin/business-hours");
        assertThat(initial.statusCode()).isEqualTo(200);
        assertThat(initial.body())
                .contains("\"timezone\":\"UTC\"")
                .contains("\"dayOfWeek\":1")
                .contains("\"start\":\"09:00\"")
                .contains("\"end\":\"17:00\"");

        HttpResponse<String> updated = mutate(
                session,
                "PUT",
                "/api/v1/admin/business-hours",
                """
                {
                  "timezone":"Europe/Warsaw",
                  "intervals":[
                    {"dayOfWeek":1,"start":"08:00","end":"12:00"},
                    {"dayOfWeek":1,"start":"13:00","end":"17:00"},
                    {"dayOfWeek":3,"start":"10:00","end":"16:00"}
                  ]
                }
                """);

        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body())
                .contains("\"timezone\":\"Europe/Warsaw\"")
                .contains("\"updatedBy\":\"" + delegatedId + "\"")
                .contains("\"dayOfWeek\":3")
                .doesNotContain("\"dayOfWeek\":2");
        assertThat(queryInt("SELECT count(*) FROM business_hours WHERE active = TRUE")).isEqualTo(1);
        assertThat(queryInt("SELECT count(*) FROM business_hour_intervals")).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT updated_by FROM business_hours WHERE active = TRUE",
                        String.class))
                .isEqualTo(delegatedId.toString());
    }

    @Test
    void invalidSchedulesAreRejectedWithoutReplacingThePersistedSchedule() throws Exception {
        UUID delegatedId = createUser("validator@example.com");
        jdbc.update(
                "INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'manage_schedule')",
                delegatedId);
        CookieManager session = login("validator@example.com");

        assertValidationFailure(session, """
                {"timezone":"UTC","intervals":[
                  {"dayOfWeek":1,"start":"09:00","end":"12:00"},
                  {"dayOfWeek":1,"start":"11:00","end":"17:00"}
                ]}
                """, "must not overlap");
        assertValidationFailure(session, """
                {"timezone":"UTC","intervals":[
                  {"dayOfWeek":1,"start":"22:00","end":"02:00"}
                ]}
                """, "overnight hours must be split");
        assertValidationFailure(
                session,
                "{\"timezone\":\"UTC\",\"intervals\":[]}",
                "at least one opening interval");
        assertValidationFailure(session, """
                {"timezone":"Not/AZone","intervals":[
                  {"dayOfWeek":1,"start":"09:00","end":"17:00"}
                ]}
                """, "valid IANA timezone id");

        assertThat(jdbc.queryForObject(
                        "SELECT timezone_id FROM business_hours WHERE active = TRUE",
                        String.class))
                .isEqualTo("UTC");
        assertThat(jdbc.queryForObject(
                        "SELECT updated_by FROM business_hours WHERE active = TRUE",
                        String.class))
                .isEqualTo("system:bootstrap");
        assertThat(queryInt("SELECT count(*) FROM business_hour_intervals")).isEqualTo(5);
    }

    @Test
    void businessHoursRoutesRequireAuthenticationAndManageSchedulePermission() throws Exception {
        CookieManager anonymous = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        assertThat(get(client(anonymous), "/api/v1/admin/business-hours").statusCode()).isEqualTo(401);

        createUser("plain@example.com");
        CookieManager plain = login("plain@example.com");
        HttpResponse<String> denied = get(client(plain), "/api/v1/admin/business-hours");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("\"code\":\"ACCESS_DENIED\"");
    }

    private static void assertValidationFailure(
            CookieManager session,
            String body,
            String expectedDetail) throws Exception {
        HttpResponse<String> response = mutate(
                session,
                "PUT",
                "/api/v1/admin/business-hours",
                body);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .contains("\"code\":\"VALIDATION_FAILED\"")
                .contains(expectedDetail);
    }

    private static void resetDefaultBusinessHours() {
        UUID scheduleId = jdbc.queryForObject("""
                INSERT INTO business_hours (timezone_id, active, updated_by)
                VALUES ('UTC', TRUE, 'system:bootstrap')
                RETURNING id
                """, UUID.class);
        for (int day = 1; day <= 5; day++) {
            jdbc.update("""
                    INSERT INTO business_hour_intervals (
                        business_hours_id, day_of_week, start_time, end_time
                    ) VALUES (?, ?, TIME '09:00', TIME '17:00')
                    """, scheduleId, day);
        }
    }

    private static UUID createUser(String email) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, display_name, password_hash, role, active) "
                        + "VALUES (?, ?, ?, 'USER', TRUE) RETURNING id",
                UUID.class,
                email,
                email,
                encoder.encode(testCredential()));
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

    private static int queryInt(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static String testCredential() {
        return String.join("-", "test", "only", "business", "hours", "credential");
    }
}
