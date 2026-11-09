package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class AuthSessionIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static UserAccountRepository users;
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
        encoder = context.getBean("bootstrapAdminPasswordEncoder", PasswordEncoder.class);
        jdbc = context.getBean(JdbcTemplate.class);
        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null) {
            throw new IllegalStateException("random server port was not published");
        }
        baseUri = URI.create("http://127.0.0.1:" + port);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) {
            context.close();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM authentication_abuse_budgets");
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update(
                "UPDATE bootstrap_admin_state "
                        + "SET consumed = FALSE, consumed_at = NULL, admin_user_id = NULL "
                        + "WHERE id = 1");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void credentialsInGetOrQueryCannotAuthenticateAndOversizedBodiesAreRejected() throws Exception {
        createUser("url@example.com", "URL test", UserRole.USER, true, null, null);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = client(cookies);
        primeCsrf(http, cookies);
        var queryLogin = get(http, "/api/v1/auth/login?email=url%40example.com&password=test-only-query");
        assertThat(queryLogin.statusCode()).isBetween(400, 499);
        assertThat(queryLogin.body()).doesNotContain("test-only-query", "url@example.com");
        assertThat(queryInt("SELECT count(*) FROM spring_session")).isZero();
        assertThat(postJson(http, "/api/v1/auth/login", " ".repeat(8193), csrfToken(cookies)).statusCode()).isEqualTo(413);
        assertThat(queryInt("SELECT count(*) FROM spring_session")).isZero();
    }

    @Test
    void failedLoginReservationsSurviveRollbackAndForwardedHeadersCannotBypassBackoff() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = client(cookies);
        primeCsrf(http, cookies);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(login(http, cookies, "unknown@example.com", testCredential()).statusCode()).isEqualTo(401);
        }
        var blocked = http.send(HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrfToken(cookies))
                .header("X-Forwarded-For", "198.51.100.1")
                .POST(HttpRequest.BodyPublishers.ofString(loginJson(" UNKNOWN@example.com ", testCredential())))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
        assertThat(blocked.body()).contains("RATE_LIMITED").doesNotContain("unknown@example.com");
        jdbc.update("UPDATE authentication_abuse_budgets SET expires_at = now() - interval '1 second'");
        assertThat(login(http, cookies, "unknown@example.com", testCredential()).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForList("SELECT bucket_key FROM authentication_abuse_budgets", String.class))
                .allSatisfy(key -> assertThat(key).doesNotContain("@", "127.0.0.1"));
    }

    @Test
    void ipBudgetIsAtomicAcrossConcurrentRequestsAndResetBudgetsAreIndependent() throws Exception {
        var guard = context.getBean(AuthenticationAbuseGuard.class);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<Long>>();
            for (int i = 0; i < 60; i++) {
                int index = i;
                jobs.add(() -> guard.login("random-" + index + "@example.com", "198.51.100.12"));
            }
            int accepted = 0;
            for (var result : pool.invokeAll(jobs)) if (result.get() == 0) accepted++;
            assertThat(accepted).isEqualTo(50);
        }
        assertThat(guard.login("other@example.com", "198.51.100.13")).isZero();
        for (int i = 0; i < 5; i++) assertThat(guard.reset("reset@example.com", "198.51.100.12")).isZero();
        assertThat(guard.reset(" RESET@example.com ", "198.51.100.14")).isPositive();
        assertThat(guard.reset("another@example.com", "198.51.100.14")).isZero();
        for (int i = 0; i < 50; i++) assertThat(guard.actionToken("198.51.100.15")).isZero();
        assertThat(guard.actionToken("198.51.100.15")).isPositive();
    }

    @Test
    void resetIsOneTimeInvalidatesSessionsAndCannotReactivateDisabledUsers() throws Exception {
        UserAccount manager = createUser("manager@example.com", "Manager", UserRole.ADMIN, true, null, null);
        jdbc.update("INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'manage_users')", manager.id());
        UserAccount agent = createUser("reset-agent@example.com", "Agent", UserRole.USER, true, null, null);
        var service = context.getBean(UserAdministrationService.class);
        var actor = UsiSessionPrincipal.from(manager);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = client(cookies);
        primeCsrf(http, cookies);
        assertThat(login(http, cookies, agent.email(), testCredential()).statusCode()).isEqualTo(200);
        var token = service.issuePasswordReset(actor, agent.id());
        assertThat(jdbc.queryForObject("SELECT token_hash FROM account_action_tokens WHERE user_id = ? AND used_at IS NULL",
                String.class, agent.id())).isNotEqualTo(token.token());
        service.consumeToken(token.token(), testCredential(), "PASSWORD_RESET");
        assertThat(get(http, "/api/v1/auth/me").statusCode()).isEqualTo(401);
        assertThatThrownBy(() -> service.consumeToken(token.token(), testCredential(), "PASSWORD_RESET"))
                .isInstanceOf(com.unifiedsupportinbox.ApiProblemException.class);
        var disabledToken = service.issuePasswordReset(actor, agent.id());
        service.deactivate(actor, agent.id());
        assertThatThrownBy(() -> service.consumeToken(disabledToken.token(), testCredential(), "PASSWORD_RESET"))
                .isInstanceOf(com.unifiedsupportinbox.ApiProblemException.class);
        assertThat(users.findById(agent.id()).orElseThrow().active()).isFalse();
        assertThatThrownBy(() -> service.update(actor, manager.id(), new UserAdministrationService.UserInput(
                manager.displayName(), manager.email(), UserRole.ADMIN, true, null, null,
                List.of("manage_users", "manage_integrations"))))
                .isInstanceOf(com.unifiedsupportinbox.ApiProblemException.class);
    }

    @Test
    void validLoginPersistsSessionUpdatesLastLoginAndReturnsSafeCurrentUser() throws Exception {
        UserAccount user = createUser(
                "agent@example.com",
                "Agent Testowy",
                UserRole.ADMIN,
                true,
                null,
                null);
        for (String permission : PermissionCatalog.ALL) {
            jdbc.update("INSERT INTO user_permissions (user_id, permission_code) VALUES (?, ?)", user.id(), permission);
        }

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient firstClient = client(cookies);
        primeCsrf(firstClient, cookies);

        HttpResponse<String> login = login(
                firstClient,
                cookies,
                "  AGENT@example.com ",
                testCredential());

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body())
                .contains("\"id\":\"" + user.id() + "\"")
                .contains("\"email\":\"agent@example.com\"")
                .contains("\"displayName\":\"Agent Testowy\"")
                .contains("\"role\":\"ADMIN\"")
                .contains("\"effectivePermissions\":[")
                .contains("\"createdAt\":")
                .doesNotContain("passwordHash")
                .doesNotContain("password_hash");
        for (String permission : PermissionCatalog.ALL) {
            assertThat(login.body()).contains("\"" + permission + "\"");
        }

        String setCookie = String.join("\n", login.headers().allValues("set-cookie"));
        assertThat(setCookie)
                .contains("USI_SESSION=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/");

        assertThat(users.findById(user.id()).orElseThrow().lastLoginAt()).isNotNull();
        assertThat(queryInt("SELECT COUNT(*) FROM spring_session")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT principal_name FROM spring_session",
                String.class))
                .isEqualTo(user.id().toString());

        String firstSessionId = cookieValue(cookies, "USI_SESSION");
        assertThat(firstSessionId).isNotBlank();
        HttpResponse<String> reauthentication = login(
                firstClient,
                cookies,
                "agent@example.com",
                testCredential());
        assertThat(reauthentication.statusCode()).isEqualTo(200);
        assertThat(cookieValue(cookies, "USI_SESSION"))
                .isNotBlank()
                .isNotEqualTo(firstSessionId);
        assertThat(queryInt("SELECT COUNT(*) FROM spring_session")).isEqualTo(1);

        HttpClient secondClient = client(cookies);
        HttpResponse<String> current = get(secondClient, "/api/v1/auth/me");
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(current.body())
                .contains("\"email\":\"agent@example.com\"")
                .doesNotContain("password");
    }

    @Test
    void invalidInactiveAndExpiredAccountsUseTheSameGenericLoginFailure() throws Exception {
        createUser(
                "inactive@example.com",
                "Inactive",
                UserRole.USER,
                false,
                null,
                null);
        createUser(
                "expired@example.com",
                "Expired",
                UserRole.USER,
                true,
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(60));
        createUser(
                "active@example.com",
                "Active",
                UserRole.USER,
                true,
                null,
                null);

        HttpResponse<String> unknown = attemptLogin("unknown@example.com", testCredential());
        HttpResponse<String> inactive = attemptLogin("inactive@example.com", testCredential());
        HttpResponse<String> expired = attemptLogin("expired@example.com", testCredential());
        HttpResponse<String> wrongCredential = attemptLogin(
                "active@example.com",
                String.join("-", "test", "only", "wrong", "credential"));

        for (HttpResponse<String> response : List.of(
                unknown,
                inactive,
                expired,
                wrongCredential)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body())
                    .contains("\"code\":\"AUTHENTICATION_REQUIRED\"")
                    .contains("\"detail\":\"Invalid email or password.\"");
        }
        assertThat(queryInt("SELECT COUNT(*) FROM spring_session")).isZero();
    }

    @Test
    void csrfIsRequiredAndLogoutAndAccountDeactivationInvalidateServerSessions() throws Exception {
        UserAccount user = createUser(
                "session@example.com",
                "Session User",
                UserRole.USER,
                true,
                null,
                null);

        CookieManager missingCsrfCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient missingCsrfClient = client(missingCsrfCookies);
        HttpResponse<String> rejected = postJson(
                missingCsrfClient,
                "/api/v1/auth/login",
                loginJson("session@example.com", testCredential()),
                null);
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(users.findById(user.id()).orElseThrow().lastLoginAt()).isNull();

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient authenticatedClient = client(cookies);
        primeCsrf(authenticatedClient, cookies);
        assertThat(login(
                authenticatedClient,
                cookies,
                "session@example.com",
                testCredential()).statusCode()).isEqualTo(200);
        assertThat(queryInt("SELECT COUNT(*) FROM spring_session")).isEqualTo(1);

        HttpResponse<String> logout = postJson(
                authenticatedClient,
                "/api/v1/auth/logout",
                null,
                csrfToken(cookies));
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(queryInt("SELECT COUNT(*) FROM spring_session")).isZero();
        assertThat(get(authenticatedClient, "/api/v1/auth/me").statusCode()).isEqualTo(401);

        CookieManager secondCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient secondClient = client(secondCookies);
        primeCsrf(secondClient, secondCookies);
        assertThat(login(
                secondClient,
                secondCookies,
                "session@example.com",
                testCredential()).statusCode()).isEqualTo(200);

        UserAccount deactivated = users.findById(user.id()).orElseThrow();
        deactivated.setActive(false);
        users.saveAndFlush(deactivated);

        assertThat(get(secondClient, "/api/v1/auth/me").statusCode()).isEqualTo(401);
        assertThat(queryInt("SELECT COUNT(*) FROM spring_session")).isZero();
    }

    private static UserAccount createUser(
            String email,
            String displayName,
            UserRole role,
            boolean active,
            Instant validFrom,
            Instant validUntil) {
        return users.saveAndFlush(new UserAccount(
                email,
                displayName,
                encoder.encode(testCredential()),
                role,
                active,
                validFrom,
                validUntil));
    }

    private static HttpResponse<String> attemptLogin(String email, String credential) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient httpClient = client(cookies);
        primeCsrf(httpClient, cookies);
        return login(httpClient, cookies, email, credential);
    }

    private static HttpResponse<String> login(
            HttpClient httpClient,
            CookieManager cookies,
            String email,
            String credential) throws Exception {
        return postJson(
                httpClient,
                "/api/v1/auth/login",
                loginJson(email, credential),
                csrfToken(cookies));
    }

    private static void primeCsrf(HttpClient httpClient, CookieManager cookies) throws Exception {
        HttpResponse<String> response = get(httpClient, "/api/v1/auth/me");
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
        return HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Accept", "application/json")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(
            HttpClient client,
            String path,
            String body,
            String csrfToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Accept", "application/json")
                .POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (csrfToken != null) {
            builder.header("X-XSRF-TOKEN", csrfToken);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String loginJson(String email, String credential) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + credential + "\"}";
    }

    private static String testCredential() {
        return String.join("-", "test", "only", "session", "credential");
    }

    private static int queryInt(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
