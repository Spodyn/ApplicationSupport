package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import com.unifiedsupportinbox.sla.CaseResponseCycles;
import com.unifiedsupportinbox.sla.CaseSlaInitializer;
import com.unifiedsupportinbox.sla.CaseSlaWaitingRecorder;
import com.unifiedsupportinbox.sla.CaseSlaReopenRecorder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
        String key = "pagination-" + UUID.randomUUID();
        UUID first = caseId(key + "-first");
        UUID second = caseId(key + "-second");
        var filter = new CaseListFilter(false, false, false, false, null, null, null, null, null, null, key);
        message(first, "first real message");
        message(second, "second real message");
        jdbc.update("UPDATE cases SET last_activity_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?", first);
        jdbc.update("UPDATE cases SET last_activity_at = CURRENT_TIMESTAMP WHERE id = ?", second);

        var firstPage = cases.list(agent, null, 1, CaseListView.ACTIVE, filter);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.items().getFirst().id()).isEqualTo(second);
        assertThat(firstPage.items().getFirst().lastMessagePreview()).isEqualTo("second real message");
        assertThat(firstPage.items().getFirst().unreadForCurrentUser()).isTrue();
        assertThat(firstPage.items().getFirst().lastMessageAuthor()).isEqualTo("Customer");
        assertThat(firstPage.items().getFirst().lastMessageAt()).isNotNull();
        assertThat(firstPage.quickViewCounts()).isEqualTo(new CaseListService.QuickViewCounts(2, 0, 0, 2));
        assertThat(firstPage.nextCursor()).isNotBlank();

        var secondPage = cases.list(agent, firstPage.nextCursor(), 1, CaseListView.ACTIVE, filter);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().getFirst().id()).isEqualTo(first);
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(secondPage.quickViewCounts()).isEqualTo(firstPage.quickViewCounts());
        assertThat(cases.list(secondAgent, null, 2, CaseListView.ACTIVE, filter).items()).extracting(CaseListService.CaseListItem::id)
                .containsExactly(second, first);
        assertThatThrownBy(() -> cases.list(secondAgent, firstPage.nextCursor(), 1, CaseListView.ACTIVE, filter))
                .hasMessageContaining("cursor");

        UUID readMessage = firstPage.items().getFirst().lastMessageId();
        jdbc.update("INSERT INTO case_read_states (user_id, case_id, last_read_message_id, last_read_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                agent, second, readMessage);
        assertThat(cases.list(agent, null, 2, CaseListView.ACTIVE, filter).items().stream()
                .filter(item -> item.id().equals(second)).findFirst().orElseThrow().unreadForCurrentUser()).isFalse();
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

    @Test
    void slaAttentionExcludesResolvedIgnoredAndWaitingEvenWithRecordedBreach() {
        UUID agent = user(true);
        String marker = "sla-attention-" + UUID.randomUUID();
        UUID active = caseId(marker + "-active"), resolved = caseId(marker + "-resolved");
        UUID ignored = caseId(marker + "-ignored"), waiting = caseId(marker + "-waiting");
        jdbc.update("UPDATE cases SET status = 'RESOLVED', resolved_at = now() WHERE id = ?", resolved);
        jdbc.update("UPDATE cases SET status = 'IGNORED', ignored_at = now() WHERE id = ?", ignored);
        jdbc.update("UPDATE cases SET status = 'WAITING_FOR_CUSTOMER', waiting_until = now() + interval '1 hour' WHERE id = ?", waiting);
        for (UUID id : java.util.List.of(active, resolved, ignored, waiting)) {
            jdbc.update("""
                    INSERT INTO case_sla (case_id, policy_id, first_response_started_at, first_response_due_at,
                        unclaimed_started_at, unclaimed_warning_at, unclaimed_breach_at, state, paused_at)
                    SELECT ?, id, now() - interval '2 hours', now() - interval '1 hour',
                        now() - interval '2 hours', now() - interval '1 hour', now(), 'BREACHED',
                        CASE WHEN ? THEN now() ELSE NULL END FROM sla_policies LIMIT 1
                    """, id, id.equals(waiting));
        }
        var filter = new CaseListFilter(false, false, false, true, null, null, null, null, null, null, marker);
        assertThat(cases.list(agent, null, 20, CaseListView.ACTIVE, filter).items())
                .extracting(CaseListService.CaseListItem::id).containsExactly(active);
        assertThat(context.getBean(CaseDetailService.class).detail(resolved, agent).sla().state()).isEqualTo("BREACHED");
        var shared = new CaseListFilter(false, false, false, false, null, null, null, null, null, null, marker);
        var expected = new CaseListService.QuickViewCounts(2, 0, 1, 4);
        var sla = cases.list(agent, null, 1, CaseListView.ACTIVE, shared, CaseListService.QuickView.SLA);
        assertThat(sla.items()).extracting(CaseListService.CaseListItem::id).containsExactly(active);
        assertThat(sla.quickViewCounts()).isEqualTo(expected);
        var all = cases.list(agent, null, 1, CaseListView.ACTIVE, shared, CaseListService.QuickView.ALL);
        assertThat(all.nextCursor()).isNotBlank();
        assertThat(all.quickViewCounts()).isEqualTo(expected);
        var mine = cases.list(agent, null, 1, CaseListView.ACTIVE, shared, CaseListService.QuickView.MINE);
        assertThat(mine.items()).isEmpty();
        assertThat(mine.quickViewCounts()).isEqualTo(expected);
        assertThatThrownBy(() -> cases.list(agent, all.nextCursor(), 1, CaseListView.ACTIVE, shared,
                CaseListService.QuickView.UNASSIGNED)).hasMessageContaining("cursor");
    }

    @Test
    void simplifiedQueueSharesCountsAndFiltersWhileLegacyWaitingRemainsVisible() {
        UUID agent = user(true);
        String marker = "simplified-" + UUID.randomUUID();
        UUID fresh = caseId(marker + "-fresh");
        UUID partial = caseId(marker + "-partial");
        UUID owned = caseId(marker + "-owned");
        UUID waiting = caseId(marker + "-waiting");
        UUID ignored = caseId(marker + "-ignored");
        UUID resolved = caseId(marker + "-resolved");
        jdbc.update("UPDATE cases SET status = 'PARTIALLY_IGNORED' WHERE id = ?", partial);
        jdbc.update("UPDATE cases SET status = 'VERIFICATION', owner_user_id = ?, claimed_at = now() WHERE id = ?", agent, owned);
        jdbc.update("UPDATE cases SET status = 'WAITING_FOR_CUSTOMER', waiting_until = now() + interval '1 hour' WHERE id = ?", waiting);
        jdbc.update("UPDATE cases SET status = 'IGNORED', ignored_at = now() WHERE id = ?", ignored);
        jdbc.update("UPDATE cases SET status = 'RESOLVED', resolved_at = now() WHERE id = ?", resolved);

        var legacy = new CaseListFilter(false, false, false, false, null, null, null, null, null, null, marker);
        var simplified = new CaseListFilter(false, false, false, false, null, null, null, null, null,
                null, marker, null, true);
        var legacyUnassigned = cases.list(agent, null, 10, CaseListView.ACTIVE, legacy, CaseListService.QuickView.UNASSIGNED);
        assertThat(legacyUnassigned.items()).extracting(CaseListService.CaseListItem::id).containsExactlyInAnyOrder(fresh, partial, waiting);
        var unassigned = cases.list(agent, null, 10, CaseListView.ACTIVE, simplified, CaseListService.QuickView.UNASSIGNED);
        assertThat(unassigned.items()).extracting(CaseListService.CaseListItem::id).containsExactlyInAnyOrder(fresh, partial);
        assertThat(unassigned.items()).extracting(CaseListService.CaseListItem::workflowStatus).containsOnly(CaseWorkflowStatus.NEW);
        assertThat(unassigned.quickViewCounts()).isEqualTo(new CaseListService.QuickViewCounts(2, 1, 0, 6));
        var archive = new CaseListFilter(false, false, false, false, null, CaseListFilter.StatusScope.TERMINAL,
                null, null, null, null, marker, CaseWorkflowStatus.RESOLVED, true);
        var archived = cases.list(agent, null, 10, CaseListView.ACTIVE, archive, CaseListService.QuickView.ALL);
        assertThat(archived.items()).extracting(CaseListService.CaseListItem::id).containsExactlyInAnyOrder(ignored, resolved);
        assertThat(archived.items()).extracting(CaseListService.CaseListItem::workflowStatus).containsOnly(CaseWorkflowStatus.RESOLVED);
        assertThat(archived.items()).extracting(CaseListService.CaseListItem::status)
                .containsExactlyInAnyOrder(com.unifiedsupportinbox.cases.CaseStatus.IGNORED,
                        com.unifiedsupportinbox.cases.CaseStatus.RESOLVED);
        var details = context.getBean(CaseDetailService.class);
        assertThat(details.detail(owned, agent).workflowStatus()).isEqualTo(CaseWorkflowStatus.OPEN);
        assertThat(details.detail(waiting, agent).workflowStatus()).isEqualTo(CaseWorkflowStatus.WAITING_ON_CUSTOMER);
    }

    @Test
    void keepsFirstReplyAndTwoNextReplyCyclesOnTheOriginalBusinessSchedule() {
        UUID agent = user(true);
        UUID id = caseId("response-cycles-" + UUID.randomUUID());
        var init = context.getBean(CaseSlaInitializer.class);
        var cycles = context.getBean(CaseResponseCycles.class);
        var waiting = context.getBean(CaseSlaWaitingRecorder.class);
        var reopen = context.getBean(CaseSlaReopenRecorder.class);
        var detail = context.getBean(CaseDetailService.class);
        Instant friday = Instant.parse("2026-03-27T15:53:00Z");
        Instant created = friday.plusSeconds(60);
        Instant monday = Instant.parse("2026-03-30T07:00:00Z");
        jdbc.update("UPDATE cases SET created_at = ? WHERE id = ?", utc(created), id);
        String originalTimezone = jdbc.queryForObject("SELECT timezone_id FROM business_hours WHERE active", String.class);
        try {
            jdbc.update("UPDATE business_hours SET timezone_id = 'Europe/Warsaw' WHERE active");
            init.initialize(id, created, friday);
            init.initialize(id, created.plusSeconds(60), friday.plusSeconds(60));
            assertThat(detail.detail(id, agent).sla().firstResponseStartedAt()).isEqualTo(friday);
            assertThat(detail.detail(id, agent).sla().firstResponseDueAt()).isEqualTo(monday.plusSeconds(8 * 60));
            assertThat(detail.detail(id, agent).responseSla().warningAt()).isEqualTo(monday.plusSeconds(4 * 60 + 15));
            cycles.systemResponse(id);
            assertThat(detail.detail(id, agent).sla().firstResponseCompletedAt()).isNull();

            Instant firstDelivery = monday.plusSeconds(7 * 60);
            cycles.humanResponse(id, UUID.randomUUID(), firstDelivery);
            waiting.pauseForWaiting(id, firstDelivery);
            Instant firstInbound = monday.plusSeconds(12 * 60);
            waiting.resumeAfterWaiting(id, firstInbound);

            // A later settings edit cannot change an existing Case's response targets.
            jdbc.update("UPDATE business_hours SET timezone_id = 'UTC' WHERE active");
            UUID opening = UUID.randomUUID();
            cycles.customerMessage(id, opening, firstInbound);
            cycles.customerMessage(id, opening, firstInbound.plusSeconds(120));
            cycles.customerMessage(id, UUID.randomUUID(), firstInbound.plusSeconds(300));
            var firstRound = detail.detail(id, agent).responseCycles().getFirst();
            assertThat(firstRound.startedAt()).isEqualTo(firstInbound);
            assertThat(firstRound.warningAt()).isEqualTo(firstInbound.plusSeconds(22 * 60 + 30));
            assertThat(firstRound.dueAt()).isEqualTo(firstInbound.plusSeconds(30 * 60));

            Instant secondDelivery = firstInbound.plusSeconds(31 * 60);
            cycles.humanResponse(id, UUID.randomUUID(), secondDelivery);
            cycles.customerMessage(id, UUID.randomUUID(), secondDelivery.plusSeconds(60));
            var twoRounds = detail.detail(id, agent).responseCycles();
            assertThat(twoRounds).hasSize(2);
            assertThat(twoRounds.get(1).outcome()).isEqualTo("RESPONDED");
            assertThat(twoRounds.get(1).breached()).isTrue();
            assertThat(twoRounds.getFirst().endedAt()).isNull();

            Instant resolved = monday.plusSeconds(52 * 60);
            jdbc.update("UPDATE cases SET status = 'RESOLVED', resolved_at = ? WHERE id = ?", utc(resolved), id);
            cycles.close(id);
            cycles.close(id);
            var closed = detail.detail(id, agent);
            assertThat(closed.responseSla().state()).isEqualTo("NEUTRAL");
            assertThat(closed.responseSla().historicallyBreached()).isTrue();
            assertThat(closed.responseCycles().getFirst().outcome()).isEqualTo("RESOLVED");
            assertThat(closed.resolutionWallSeconds()).isEqualTo(java.time.Duration.between(created, resolved).getSeconds());
            assertThat(closed.resolutionWorkingSeconds()).isEqualTo(53 * 60L);

            Instant reopened = resolved.plusSeconds(300);
            jdbc.update("UPDATE cases SET status = 'NEW', resolved_at = NULL WHERE id = ?", id);
            reopen.restartUnclaimed(id, reopened);
            var restored = detail.detail(id, agent);
            assertThat(restored.sla().firstResponseCompletedAt()).isEqualTo(firstDelivery);
            assertThat(restored.sla().unclaimedStartedAt()).isEqualTo(reopened);
            assertThat(restored.responseSla().historicallyBreached()).isTrue();
            assertThat(restored.responseSla().dueAt()).isNull();
        } finally {
            jdbc.update("UPDATE business_hours SET timezone_id = ? WHERE active", originalTimezone);
        }
    }

    @Test
    void changesAttentionAtExactBusinessWarningAndDueInstantsAndSortsByActiveTarget() {
        Instant due = Instant.parse("2026-03-30T08:00:00Z");
        Instant warning = due.minusSeconds(225);
        assertThat(responseState("OPEN", warning, due, warning.minusMillis(1))).isEqualTo("ON_TRACK");
        assertThat(responseState("OPEN", warning, due, warning)).isEqualTo("WARNING");
        assertThat(responseState("OPEN", warning, due, due)).isEqualTo("BREACHED");
        assertThat(responseState("WAITING_ON_CUSTOMER", warning, due, due)).isEqualTo("PAUSED");
        assertThat(responseState("RESOLVED", warning, due, due)).isEqualTo("NEUTRAL");

        UUID agent = user(true);
        String marker = "priority-" + UUID.randomUUID();
        UUID late = rankedCase(marker + "-late", -10, -5);
        UUID warned = rankedCase(marker + "-warned", -2, 5);
        UUID healthyEarly = rankedCase(marker + "-healthy-early", 5, 10);
        UUID healthyLate = rankedCase(marker + "-healthy-late", 10, 20);
        UUID waiting = rankedCase(marker + "-waiting", -10, -5);
        UUID resolved = rankedCase(marker + "-resolved", -10, -5);
        jdbc.update("UPDATE cases SET status='WAITING_FOR_CUSTOMER', waiting_until=now()+interval '1 hour' WHERE id=?", waiting);
        jdbc.update("UPDATE cases SET status='RESOLVED', resolved_at=now() WHERE id=?", resolved);
        var filter = new CaseListFilter(false, false, false, false, null, null, null, null, null, null, marker);
        var first = cases.list(agent, null, 2, CaseListView.ACTIVE, filter);
        assertThat(first.items()).extracting(CaseListService.CaseListItem::id).containsExactly(late, warned);
        assertThat(first.quickViewCounts().sla()).isEqualTo(2);
        var second = cases.list(agent, first.nextCursor(), 2, CaseListView.ACTIVE, filter);
        assertThat(second.items()).extracting(CaseListService.CaseListItem::id).containsExactly(healthyEarly, healthyLate);
        var third = cases.list(agent, second.nextCursor(), 2, CaseListView.ACTIVE, filter);
        assertThat(third.items()).extracting(CaseListService.CaseListItem::id).containsExactly(waiting, resolved);
    }

    private static UUID rankedCase(String marker, int warning, int deadline) {
        UUID id = caseId(marker);
        jdbc.update("""
                INSERT INTO case_sla (case_id, policy_id, first_response_started_at, first_response_due_at,
                    first_response_warning_at, unclaimed_started_at, unclaimed_warning_at, unclaimed_breach_at)
                SELECT ?, id, now() - interval '1 hour', now() + ? * interval '1 minute',
                    now() + ? * interval '1 minute', now(), now(), now() FROM sla_policies WHERE active
                """, id, deadline, warning);
        return id;
    }

    private static String responseState(String workflow, Instant warning, Instant due, Instant at) {
        return jdbc.queryForObject("SELECT response_sla_state(?, ?, ?, ?)", String.class,
                workflow, utc(warning), utc(due), utc(at));
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
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
