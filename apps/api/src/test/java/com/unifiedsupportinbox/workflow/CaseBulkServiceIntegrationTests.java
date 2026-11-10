package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.*;
import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.PersistenceFixtures;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

@Tag("integration")
class CaseBulkServiceIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseBulkService bulk;
    @BeforeAll static void start() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class).profiles("test").run(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.session.jdbc.initialize-schema=never", "--usi.bootstrap-admin.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        bulk = context.getBean(CaseBulkService.class);
    }
    @AfterAll static void stop() { if (context != null) context.close(); POSTGRES.stop(); }

    @Test void resolvesOnlyOwnedEligibleCasesAndReplaysAllOutcomesWithoutDuplicateEffects() {
        UUID actor = user(false), other = user(false);
        UUID owned = newCase(), foreign = newCase(), unclaimed = newCase(), missing = UUID.randomUUID();
        own(owned, actor); own(foreign, other);
        var request = request(CaseBulkService.Action.RESOLVE, null, owned, foreign, unclaimed, missing);
        String key = UUID.randomUUID().toString();
        var first = bulk.execute(actor, key, request, "bulk-integration");
        assertThat(statuses(first)).containsExactly("SUCCEEDED", "SKIPPED", "SKIPPED", "SKIPPED");
        assertThat(state(owned)).isEqualTo("RESOLVED");
        assertThat(state(foreign)).isEqualTo("VERIFICATION");
        own(unclaimed, actor); // A previously skipped item must stay skipped on replay.
        assertThat(bulk.execute(actor, key, request, "bulk-replay")).isEqualTo(first);
        assertThat(state(unclaimed)).isEqualTo("VERIFICATION");
        assertThat(count("audit_events", "entity_id", owned)).isEqualTo(1);
        assertThat(count("outbox_events", "aggregate_id", owned)).isEqualTo(1);
        assertThatThrownBy(() -> bulk.execute(actor, key, request(CaseBulkService.Action.CLAIM, null, newCase()), "bulk-change"))
                .isInstanceOf(ApiProblemException.class).hasMessageContaining("different request");
    }

    @Test void assignmentRequiresExplicitAdminPermissionAndRechecksTargetEligibilityPerCase() {
        UUID ordinary = user(false), admin = user(true), target = user(false);
        UUID first = newCase(), second = newCase(), voted = newCase(), terminal = newCase();
        own(second, ordinary); own(terminal, ordinary);
        jdbc.update("UPDATE cases SET status = 'RESOLVED', resolved_at = now() WHERE id = ?", terminal);
        jdbc.update("INSERT INTO case_ignore_votes (case_id,user_id,weight,active,deactivated_at) VALUES (?,?,1,FALSE,now())", voted, target);
        var request = request(CaseBulkService.Action.ASSIGN, target, first, second, voted, terminal);
        for (UUID denied : List.of(ordinary, admin)) assertThatThrownBy(() -> bulk.execute(denied, UUID.randomUUID().toString(), request, "bulk-denied"))
                .isInstanceOf(ApiProblemException.class);
        jdbc.update("INSERT INTO user_permissions (user_id, permission_code) VALUES (?, 'reassign_cases')", admin);
        var result = bulk.execute(admin, UUID.randomUUID().toString(), request, "bulk-assign");
        assertThat(statuses(result)).containsExactly("SUCCEEDED", "SUCCEEDED", "SKIPPED", "SKIPPED");
        assertThat(jdbc.queryForList("SELECT owner_user_id FROM cases WHERE id IN (?,?)", UUID.class, first, second)).containsOnly(target);
        jdbc.update("UPDATE users SET active = FALSE WHERE id = ?", target);
        assertThat(statuses(bulk.execute(admin, UUID.randomUUID().toString(), request(CaseBulkService.Action.ASSIGN, target, newCase()), "bulk-inactive")))
                .containsExactly("SKIPPED");
    }

    @Test void concurrentDuplicateIgnoreBatchesCastOneVoteAndRetainAuditAndOutbox() throws Exception {
        UUID actor = user(false), id = newCase();
        var request = request(CaseBulkService.Action.IGNORE, null, id);
        String key = UUID.randomUUID().toString();
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { go.await(); return bulk.execute(actor, key, request, "bulk-race-a"); });
            var second = pool.submit(() -> { go.await(); return bulk.execute(actor, key, request, "bulk-race-b"); });
            go.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
        }
        assertThat(state(id)).isEqualTo("PARTIALLY_IGNORED");
        assertThat(count("case_ignore_votes", "case_id", id)).isEqualTo(1);
        assertThat(count("audit_events", "entity_id", id)).isEqualTo(1);
        assertThat(statuses(bulk.execute(actor, UUID.randomUUID().toString(), request(CaseBulkService.Action.CLAIM, null, id), "bulk-voter")))
                .containsExactly("SKIPPED");
    }

    @Test void failedItemRollsBackAndDoesNotPreventLaterItemsOrBecomeSuccessfulOnRetry() {
        UUID actor = user(false), bad = newCase(), good = newCase();
        own(bad, actor); own(good, actor);
        String function = "bulk_fail_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.entity_id = '" + bad + "'::uuid THEN RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        String key = UUID.randomUUID().toString();
        var request = request(CaseBulkService.Action.RESOLVE, null, bad, good);
        JsonNode result;
        try { result = bulk.execute(actor, key, request, "bulk-failure"); }
        finally { jdbc.execute("DROP TRIGGER " + function + " ON audit_events"); jdbc.execute("DROP FUNCTION " + function + "()"); }
        assertThat(statuses(result)).containsExactly("FAILED", "SUCCEEDED");
        assertThat(state(bad)).isEqualTo("VERIFICATION");
        assertThat(count("audit_events", "entity_id", bad)).isZero();
        assertThat(bulk.execute(actor, key, request, "bulk-failed-replay")).isEqualTo(result);
    }

    @Test void httpContractRequiresAuthenticationCsrfAndPerActionPermission() throws Exception {
        URI origin = URI.create("http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var http = HttpClient.newBuilder().cookieHandler(cookies).build();
        http.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String id = newCase().toString();
        String selection = "{\"caseIds\":[\"" + id + "\"],\"action\":\"CLAIM\",\"targetUserId\":null}";
        assertThat(post(http, cookies, origin, "/api/v1/cases/bulk", selection, true).statusCode()).isEqualTo(401);
        UUID actor = user(false);
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, actor);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", context.getBean(PasswordEncoder.class).encode("test-only-bulk-credential"), actor);
        assertThat(post(http, cookies, origin, "/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"test-only-bulk-credential\"}", true).statusCode()).isEqualTo(200);
        http.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post(http, cookies, origin, "/api/v1/cases/bulk", selection, false).statusCode()).isEqualTo(403);
        String assign = selection.replace("CLAIM", "ASSIGN").replace("null", "\"" + actor + "\"");
        assertThat(post(http, cookies, origin, "/api/v1/cases/bulk", assign, true).statusCode()).isEqualTo(403);
        assertThat(state(UUID.fromString(id))).isEqualTo("NEW");
        assertThat(post(http, cookies, origin, "/api/v1/cases/bulk", "{\"caseIds\":[],\"action\":\"CLAIM\"}", true).statusCode()).isEqualTo(400);
        var response = post(http, cookies, origin, "/api/v1/cases/bulk", selection, true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("SUCCEEDED").doesNotContain("password", "test-only-bulk-credential");
        assertThat(state(UUID.fromString(id))).isEqualTo("VERIFICATION");
    }

    private static HttpResponse<String> post(HttpClient http, CookieManager cookies, URI origin, String path, String body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve(path)).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (csrf) request.header("X-XSRF-TOKEN", cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(java.net.HttpCookie::getValue).findFirst().orElseThrow());
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static CaseBulkService.Request request(CaseBulkService.Action action, UUID target, UUID... ids) { return new CaseBulkService.Request(List.of(ids), action, target); }
    private static List<String> statuses(JsonNode result) { return result.path("items").valueStream().map(item -> item.path("status").asString()).toList(); }
    private static UUID user(boolean admin) {
        var fixture = new PersistenceFixtures(jdbc); UUID id = fixture.user().id();
        if (admin) jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", id);
        return id;
    }
    private static UUID newCase() {
        var fixture = new PersistenceFixtures(jdbc);
        return jdbc.queryForObject("INSERT INTO cases (customer_id,integration_id,channel_id,provider,external_conversation_id) VALUES (?,?,?,'SLACK',?) RETURNING id",
                UUID.class, fixture.customer().id(), fixture.integration().id(), fixture.channel().id(), UUID.randomUUID().toString());
    }
    private static void own(UUID id, UUID owner) { jdbc.update("UPDATE cases SET status = 'VERIFICATION', owner_user_id = ?, claimed_at = now() WHERE id = ?", owner, id); }
    private static String state(UUID id) { return jdbc.queryForObject("SELECT status FROM cases WHERE id = ?", String.class, id); }
    private static int count(String table, String column, UUID id) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id); }
}
