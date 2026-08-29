package com.unifiedsupportinbox.customer.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class CustomerIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static PasswordEncoder encoder;
    private static ObjectMapper json;
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
        json = context.getBean(ObjectMapper.class);
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
        jdbc.execute("DROP TABLE IF EXISTS customer_reference_probe");
        jdbc.update("DELETE FROM customers");
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("UPDATE bootstrap_admin_state SET consumed = FALSE, consumed_at = NULL, admin_user_id = NULL WHERE id = 1");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void adminCanCreateEditListAndDeactivateWithoutDeletingReferencedCustomer() throws Exception {
        createUser("admin@example.com", "ADMIN");
        CookieManager cookies = login("admin@example.com");

        HttpResponse<String> created = mutate(cookies, "POST", "/api/v1/admin/customers",
                "{\"name\":\"Acme Corp\",\"externalRef\":\" CRM-42 \"}");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode createdJson = json.readTree(created.body());
        UUID customerId = UUID.fromString(createdJson.get("id").asText());
        assertThat(customerId.version()).isEqualTo(7);
        assertThat(createdJson.get("externalRef").asText()).isEqualTo("CRM-42");

        HttpResponse<String> updated = mutate(cookies, "PUT", "/api/v1/admin/customers/" + customerId,
                "{\"name\":\"Acme International\",\"externalRef\":\"CRM-42\"}");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body()).contains("Acme International");

        HttpResponse<String> listed = get(client(cookies), "/api/v1/admin/customers");
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains("Acme International");

        jdbc.execute("CREATE TABLE customer_reference_probe (id INTEGER PRIMARY KEY, customer_id UUID NOT NULL REFERENCES customers(id))");
        jdbc.update("INSERT INTO customer_reference_probe (id, customer_id) VALUES (1, ?)", customerId);

        HttpResponse<String> deactivated = mutate(cookies, "POST",
                "/api/v1/admin/customers/" + customerId + "/deactivate", null);
        assertThat(deactivated.statusCode()).isEqualTo(200);
        assertThat(json.readTree(deactivated.body()).get("active").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT customer_id FROM customer_reference_probe WHERE id = 1", UUID.class))
                .isEqualTo(customerId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Integer.class, customerId)).isEqualTo(1);
    }

    @Test
    void normalizedNameAndExternalReferenceAreUnique() throws Exception {
        createUser("admin@example.com", "ADMIN");
        CookieManager cookies = login("admin@example.com");
        assertThat(mutate(cookies, "POST", "/api/v1/admin/customers",
                "{\"name\":\"Acme Corp\",\"externalRef\":\"CRM-42\"}").statusCode()).isEqualTo(201);

        HttpResponse<String> duplicateName = mutate(cookies, "POST", "/api/v1/admin/customers",
                "{\"name\":\"acme corp\",\"externalRef\":\"OTHER\"}");
        assertThat(duplicateName.statusCode()).isEqualTo(409);
        assertThat(duplicateName.body()).contains("\"code\":\"CONFLICT\"");

        HttpResponse<String> duplicateRef = mutate(cookies, "POST", "/api/v1/admin/customers",
                "{\"name\":\"Other Customer\",\"externalRef\":\"crm-42\"}");
        assertThat(duplicateRef.statusCode()).isEqualTo(409);
        assertThat(duplicateRef.body()).contains("\"code\":\"CONFLICT\"");
    }

    @Test
    void customerAdminRoutesRequireAuthenticationAndAdminRole() throws Exception {
        CookieManager anonymous = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        primeCsrf(client(anonymous), anonymous);
        assertThat(get(client(anonymous), "/api/v1/admin/customers").statusCode()).isEqualTo(401);

        createUser("user@example.com", "USER");
        CookieManager user = login("user@example.com");
        HttpResponse<String> denied = get(client(user), "/api/v1/admin/customers");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("\"code\":\"ACCESS_DENIED\"");
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

    private static HttpResponse<String> mutate(CookieManager cookies, String method, String path, String body) throws Exception {
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
                HttpRequest.newBuilder(baseUri.resolve(path)).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String testCredential() {
        return String.join("-", "test", "only", "customer", "credential");
    }
}
