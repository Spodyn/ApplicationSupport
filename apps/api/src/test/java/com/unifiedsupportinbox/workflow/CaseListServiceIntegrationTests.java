package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.UUID;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CaseListServiceIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseListService cases;
    private static URI baseUri;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                        "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.session.jdbc.initialize-schema=never", "--usi.bootstrap-admin.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        cases = context.getBean(CaseListService.class);
        baseUri = URI.create("http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void listsSharedPersistedCasesWithUnreadPreviewAndSignedPagination() {
        UUID agent = user(true);
        UUID secondAgent = user(true);
        UUID first = caseId("first-" + UUID.randomUUID());
        UUID second = caseId("second-" + UUID.randomUUID());
        message(first, "first real message");
        message(second, "second real message");
        jdbc.update("UPDATE cases SET last_activity_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?", first);
        jdbc.update("UPDATE cases SET last_activity_at = CURRENT_TIMESTAMP WHERE id = ?", second);

        var firstPage = cases.list(agent, null, 1);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.items().getFirst().id()).isEqualTo(second);
        assertThat(firstPage.items().getFirst().lastMessagePreview()).isEqualTo("second real message");
        assertThat(firstPage.items().getFirst().unreadForCurrentUser()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();

        var secondPage = cases.list(agent, firstPage.nextCursor(), 1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().getFirst().id()).isEqualTo(first);
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(cases.list(secondAgent, null, 2).items()).extracting(CaseListService.CaseListItem::id)
                .containsExactly(second, first);
        assertThatThrownBy(() -> cases.list(secondAgent, firstPage.nextCursor(), 1))
                .hasMessageContaining("cursor");

        UUID readMessage = firstPage.items().getFirst().lastMessageId();
        jdbc.update("INSERT INTO case_read_states (user_id, case_id, last_read_message_id, last_read_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                agent, second, readMessage);
        assertThat(cases.list(agent, null, 2).items().stream()
                .filter(item -> item.id().equals(second)).findFirst().orElseThrow().unreadForCurrentUser()).isFalse();
    }

    @Test
    void personalSnoozeHidesOnlyForActorAndHasDedicatedView() {
        UUID actor = user(true);
        UUID other = user(true);
        UUID active = caseId("snoozed-active-" + UUID.randomUUID());
        UUID expired = caseId("snoozed-expired-" + UUID.randomUUID());
        UUID terminal = caseId("snoozed-terminal-" + UUID.randomUUID());
        message(active, "active snooze");
        message(expired, "expired snooze");
        message(terminal, "terminal snooze");

        jdbc.update("""
                INSERT INTO case_snoozes (case_id, user_id, until_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, active, actor);
        jdbc.update("""
                INSERT INTO case_snoozes (case_id, user_id, until_at, created_at)
                VALUES (?, ?, CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP - INTERVAL '2 hours')
                """, expired, actor);
        jdbc.update("""
                INSERT INTO case_snoozes (case_id, user_id, until_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, terminal, actor);
        jdbc.update("UPDATE cases SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE id = ?", terminal);

        assertThat(cases.list(actor, null, 100, CaseListView.ACTIVE).items())
                .extracting(CaseListService.CaseListItem::id)
                .contains(expired, terminal)
                .doesNotContain(active);
        assertThat(cases.list(actor, null, 100, CaseListView.SNOOZED).items())
                .extracting(CaseListService.CaseListItem::id)
                .contains(active)
                .doesNotContain(expired, terminal);

        assertThat(cases.list(other, null, 100, CaseListView.ACTIVE).items())
                .extracting(CaseListService.CaseListItem::id)
                .contains(active, expired, terminal);
        assertThat(cases.list(other, null, 100, CaseListView.SNOOZED).items())
                .extracting(CaseListService.CaseListItem::id)
                .doesNotContain(active, expired, terminal);
    }

    @Test
    void listCursorIsScopedToPersonalView() {
        UUID actor = user(true);
        UUID firstSnoozed = caseId("cursor-snooze-a-" + UUID.randomUUID());
        UUID secondSnoozed = caseId("cursor-snooze-b-" + UUID.randomUUID());
        UUID active = caseId("cursor-active-" + UUID.randomUUID());
        message(firstSnoozed, "first snoozed");
        message(secondSnoozed, "second snoozed");
        message(active, "active");
        jdbc.update("""
                INSERT INTO case_snoozes (case_id, user_id, until_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, firstSnoozed, actor);
        jdbc.update("""
                INSERT INTO case_snoozes (case_id, user_id, until_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '2 hours')
                """, secondSnoozed, actor);

        var snoozedPage = cases.list(actor, null, 1, CaseListView.SNOOZED);
        assertThat(snoozedPage.items()).hasSize(1);
        assertThat(snoozedPage.nextCursor()).isNotBlank();
        assertThatThrownBy(() -> cases.list(actor, snoozedPage.nextCursor(), 1, CaseListView.ACTIVE))
                .hasMessageContaining("cursor");
    }

    @Test
    void rejectsInactiveUsersAndInvalidLimits() {
        UUID inactive = user(false);
        assertThatThrownBy(() -> cases.list(inactive, null, 1)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> cases.list(user(true), null, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authenticatedCaseRoutesReachControllersAndAnonymousListIsRejected() throws Exception {
        UUID userId = jdbc.queryForObject("""
                INSERT INTO users (email, display_name, password_hash, role, active)
                VALUES (?, 'API Agent', ?, 'USER', TRUE) RETURNING id
                """, UUID.class, "api-agent-" + UUID.randomUUID() + "@example.test",
                context.getBean("bootstrapAdminPasswordEncoder", PasswordEncoder.class)
                        .encode("test-only-case-list-credential"));
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        assertThat(http.send(HttpRequest.newBuilder(baseUri.resolve("/api/v1/cases")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        http.send(HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String csrf = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .map(HttpCookie::getValue).findFirst().orElseThrow();
        HttpRequest login = HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email
                        + "\",\"password\":\"test-only-case-list-credential\"}"))
                .build();
        assertThat(http.send(login, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(http.send(HttpRequest.newBuilder(baseUri.resolve("/api/v1/cases")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        String missingCase = UUID.randomUUID().toString();
        HttpRequest send = HttpRequest.newBuilder(baseUri.resolve("/api/v1/cases/" + missingCase + "/messages"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"body\":\"Test reply\",\"bodyFormat\":\"PLAIN_TEXT\"}"))
                .build();
        assertThat(http.send(send, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        HttpRequest read = HttpRequest.newBuilder(baseUri.resolve("/api/v1/cases/" + missingCase + "/read-position"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"messageId\":\"" + UUID.randomUUID() + "\"}"))
                .build();
        assertThat(http.send(read, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    }

    private static UUID user(boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, 'Agent', 'USER', ?) RETURNING id
                """, UUID.class, UUID.randomUUID() + "@example.test", active);
    }

    private static UUID caseId(String key) {
        UUID customer = jdbc.queryForObject("INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class, "Customer " + key, key);
        UUID integration = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', 'Support Slack', 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, key);
        UUID channel = jdbc.queryForObject("""
                INSERT INTO channels (integration_id, external_channel_id, name, customer_id, ignored, grouping_strategy, active)
                VALUES (?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE) RETURNING id
                """, UUID.class, integration, key, customer);
        return jdbc.queryForObject("""
                INSERT INTO cases (customer_id, integration_id, channel_id, provider, external_conversation_id)
                VALUES (?, ?, ?, 'SLACK', ?) RETURNING id
                """, UUID.class, customer, integration, channel, key);
    }

    private static void message(UUID caseId, String body) {
        jdbc.update("""
                INSERT INTO messages (case_id, external_message_id, kind, author_external_id,
                                      author_name, body, inbound, provider_created_at, correlation_id)
                VALUES (?, ?, 'CUSTOMER', 'customer', 'Customer', ?, TRUE, CURRENT_TIMESTAMP, 'list-test')
                """, caseId, "message-" + UUID.randomUUID(), body);
    }
}
