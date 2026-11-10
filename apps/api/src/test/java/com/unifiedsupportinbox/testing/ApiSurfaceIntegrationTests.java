package com.unifiedsupportinbox.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class ApiSurfaceIntegrationTests {
    private static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static URI origin;
    private static List<ApiSurfaceContract.Route> routes;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll static void start() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class).profiles("test").run(
                "--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(), "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
                "--usi.object-storage.access-key=test-only-access", "--usi.object-storage.secret-key=test-only-secret",
                "--usi.object-storage.endpoint=http://127.0.0.1:9", "--management.otlp.metrics.export.enabled=false");
        origin = URI.create("http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"));
        routes = ApiSurfaceContract.routes(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class));
    }
    @AfterAll static void stop() { if (context != null) context.close(); POSTGRES.stop(); }

    @Test void everyRuntimeApiMappingHasAnOpenApiOperationAndViceVersa() throws Exception {
        Path spec = Path.of("openapi/v1/openapi.json");
        if (Boolean.getBoolean("usi.testing.exportContract")) {
            Files.writeString(Path.of("target/openapi-candidate.json"), ApiSurfaceContract.expandedContract(routes, JSON.readTree(Files.readString(spec))).toPrettyString());
        }
        var expected = JSON.readTree(Files.readString(spec));
        var missing = routes.stream().filter(route -> expected.path("paths").path(route.path()).path(route.method().toLowerCase()).isMissingNode())
                .map(route -> route.method() + " " + route.path()).toList();
        assertThat(missing).as("Runtime routes missing from transport contract").isEmpty();
        List<String> undocumentedImplementations = new ArrayList<>();
        expected.path("paths").properties().forEach(entry -> entry.getValue().properties().forEach(operation -> {
            String method = operation.getKey().toUpperCase();
            if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) return;
            if (entry.getKey().equals("/api/v1/auth/logout") && method.equals("POST")) return; // Spring Security filter endpoint
            if (routes.stream().noneMatch(route -> route.path().equals(entry.getKey()) && route.method().equals(method)))
                undocumentedImplementations.add(method + " " + entry.getKey());
        }));
        assertThat(undocumentedImplementations).as("OpenAPI operations without runtime handlers").isEmpty();
    }

    @Test void everyProtectedRouteRejectsAnonymousAndEveryAdministrativeRouteRejectsAnOrdinaryUser() throws Exception {
        var anonymousCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var anonymous = HttpClient.newBuilder().cookieHandler(anonymousCookies).build();
        anonymous.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/me")).GET().build(), HttpResponse.BodyHandlers.discarding());
        var userCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var user = HttpClient.newBuilder().cookieHandler(userCookies).build();
        user.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/me")).GET().build(), HttpResponse.BodyHandlers.discarding());
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        jdbc.update("INSERT INTO users (email, display_name, password_hash, role, active) VALUES (?, ?, ?, 'USER', TRUE)",
                "surface@example.test", "Surface User", context.getBean(PasswordEncoder.class).encode("test-only-surface-credential"));
        var login = user.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf(userCookies))
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"surface@example.test\",\"password\":\"test-only-surface-credential\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        int protectedCount = 0, administrativeCount = 0;
        for (var route : routes) {
            if (route.path().startsWith("/api/v1/providers/") || route.path().startsWith("/api/v1/provider-callbacks/")) continue;
            if (route.path().startsWith("/api/v1/auth/") && !route.path().equals("/api/v1/auth/me")) continue;
            assertDenied(anonymous, anonymousCookies, route, 401); protectedCount++;
            if (route.path().startsWith("/api/v1/admin/") || route.path().endsWith("/permissions")
                    || route.path().equals("/api/v1/statistics/overview") || route.path().equals("/api/v1/statistics/sla")) {
                assertDenied(user, userCookies, route, 403); administrativeCount++;
            }
        }
        assertThat(protectedCount).isGreaterThan(60);
        assertThat(administrativeCount).isGreaterThan(30);
        Files.writeString(Path.of("target/api-surface-evidence.json"), JSON.writeValueAsString(java.util.Map.of(
                "runtimeOperations", routes.size(), "anonymousDenials", protectedCount, "ordinaryUserDenials", administrativeCount)));
    }

    private static void assertDenied(HttpClient client, CookieManager cookies, ApiSurfaceContract.Route route, int expected) throws Exception {
        String path = route.path().replaceAll("\\{[^}]+}", "00000000-0000-4000-8000-000000000042");
        var response = client.send(HttpRequest.newBuilder(origin.resolve(path)).header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf(cookies)).header("Idempotency-Key", "test-only-surface-key")
                .method(route.method(), route.method().equals("GET") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s", route.method(), route.path()).isEqualTo(expected);
        var problem = JSON.readTree(response.body());
        assertThat(problem.path("status").asInt()).isEqualTo(expected);
        assertThat(problem.path("correlationId").asText()).isNotBlank();
        assertThat(problem.path("code").asText()).isEqualTo(expected == 401 ? "AUTHENTICATION_REQUIRED" : "ACCESS_DENIED");
    }

    @Test void collectionAndSettingsReadsReturnTheirDocumentedShapesAndRejectMalformedInputs() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/me")).GET().build(), HttpResponse.BodyHandlers.discarding());
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        var user = jdbc.queryForObject("INSERT INTO users (email, display_name, password_hash, role, active) VALUES (?, 'Surface Admin', ?, 'ADMIN', TRUE) RETURNING id",
                java.util.UUID.class, "surface-admin@example.test", context.getBean(PasswordEncoder.class).encode("test-only-surface-admin"));
        for (String permission : List.of("manage_users", "manage_integrations", "manage_sla", "manage_schedule", "manage_notifications", "view_global_statistics", "reassign_cases", "force_resolve", "view_audit"))
            jdbc.update("INSERT INTO user_permissions (user_id, permission_code) VALUES (?, ?)", user, permission);
        var login = client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf(cookies))
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"surface-admin@example.test\",\"password\":\"test-only-surface-admin\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        var contract = JSON.readTree(Files.readString(Path.of("openapi/v1/openapi.json")));
        int reads = 0;
        for (var route : routes) {
            if (!route.method().equals("GET") || route.path().contains("{")) continue;
            var response = client.send(HttpRequest.newBuilder(origin.resolve(route.path())).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("Successful read %s: %s", route.path(), response.body()).isEqualTo(200);
            var schema = contract.path("paths").path(route.path()).path("get").path("responses").path("200").path("content").path("application/json").path("schema");
            assertShape(JSON.readTree(response.body()), schema, contract, route.path()); reads++;
        }
        assertThat(reads).isGreaterThanOrEqualTo(20);
        for (String path : List.of("/api/v1/cases?limit=0", "/api/v1/cases?limit=101", "/api/v1/cases?status=OPEN", "/api/v1/cases?cursor=malformed",
                "/api/v1/admin/users?role=SUPERADMIN", "/api/v1/statistics/sla?from=not-a-date")) {
            var response = client.send(HttpRequest.newBuilder(origin.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("Invalid request %s", path).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).path("correlationId").asText()).isNotBlank();
        }
    }

    private static void assertShape(tools.jackson.databind.JsonNode value, tools.jackson.databind.JsonNode schema,
            tools.jackson.databind.JsonNode contract, String location) {
        if (schema.has("$ref")) { assertShape(value, contract.at(schema.path("$ref").asText().substring(1)), contract, location); return; }
        assertThat(schema.isMissingNode()).as("Documented schema %s", location).isFalse();
        if (value.isNull()) return; // Nullability is checked by feature-specific tests; this gate checks populated shapes.
        var declaredType = schema.path("type");
        String type = declaredType.isArray() ? java.util.stream.StreamSupport.stream(declaredType.spliterator(), false)
                .map(tools.jackson.databind.JsonNode::asText).filter(name -> !name.equals("null")).findFirst().orElse("null")
                : declaredType.asText("");
        if (type.equals("object")) {
            assertThat(value.isObject()).as(location).isTrue();
            for (var required : schema.path("required")) assertThat(value.has(required.asText())).as("%s.%s required", location, required.asText()).isTrue();
            schema.path("properties").properties().forEach(property -> {
                if (value.has(property.getKey())) assertShape(value.path(property.getKey()), property.getValue(), contract, location + "." + property.getKey());
            });
        } else if (type.equals("array")) {
            assertThat(value.isArray()).as(location).isTrue();
            for (var item : value) assertShape(item, schema.path("items"), contract, location + "[]");
        } else if (type.equals("string")) assertThat(value.isTextual()).as(location).isTrue();
        else if (type.equals("boolean")) assertThat(value.isBoolean()).as(location).isTrue();
        else if (type.equals("integer") || type.equals("number")) assertThat(value.isNumber()).as(location).isTrue();
    }
    private static String csrf(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue).findFirst().orElseThrow();
    }
}
