package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.sla.CaseSlaWaitingRecorder;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class WaitingTimeoutServiceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Autowired private WaitingTimeoutService service;
    @Autowired private CaseSlaWaitingRecorder waitingSla;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("usi.outbox.relay-enabled", () -> false);
        registry.add("usi.bootstrap-admin.enabled", () -> false);
    }

    @AfterAll
    static void stopInfrastructure() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        TestInfrastructure.resetPostgres(POSTGRES);
    }

    @Test
    void dueWaitingCaseReturnsToNewWithoutCreatingFakeMessage() {
        UUID caseId = createCase("WAITING_FOR_CUSTOMER", true);

        assertThat(service.processDue(100)).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?", String.class, caseId))
                .isEqualTo("NEW");
        assertThat(jdbc.queryForObject(
                "SELECT waiting_until FROM cases WHERE id = ?", Object.class, caseId))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT owner_user_id FROM cases WHERE id = ?", Object.class, caseId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE action = 'CASE_WAITING_TIMEOUT' AND entity_id = ?",
                Integer.class,
                caseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'case.updated' AND aggregate_id = ?",
                Integer.class,
                caseId)).isEqualTo(1);

        assertThat(service.processDue(100)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE action = 'CASE_WAITING_TIMEOUT' AND entity_id = ?",
                Integer.class,
                caseId)).isEqualTo(1);
    }

    @Test
    void futureWaitingAndTerminalCasesAreIgnored() {
        UUID future = createCase("WAITING_FOR_CUSTOMER", false);
        UUID resolved = createCase("RESOLVED", true);

        assertThat(service.processDue(100)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?", String.class, future))
                .isEqualTo("WAITING_FOR_CUSTOMER");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?", String.class, resolved))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
    }

    @Test
    void timeoutResumesThePolicyPausedSlaClock() {
        UUID caseId = createCase("WAITING_FOR_CUSTOMER", true);
        seedCaseSla(caseId);
        Instant pausedAt = Instant.now().minusSeconds(3600);
        waitingSla.pauseForWaiting(caseId, pausedAt);

        java.time.OffsetDateTime unclaimedWarningBefore = jdbc.queryForObject(
                "SELECT unclaimed_warning_at FROM case_sla WHERE case_id = ?",
                java.time.OffsetDateTime.class,
                caseId);

        assertThat(service.processDue(1)).isEqualTo(1);

        java.time.OffsetDateTime unclaimedWarningAfter = jdbc.queryForObject(
                "SELECT unclaimed_warning_at FROM case_sla WHERE case_id = ?",
                java.time.OffsetDateTime.class,
                caseId);
        assertThat(jdbc.queryForObject(
                "SELECT paused_at FROM case_sla WHERE case_id = ?", Object.class, caseId))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT total_paused_seconds FROM case_sla WHERE case_id = ?", Long.class, caseId))
                .isBetween(3590L, 3610L);
        assertThat(java.time.Duration.between(
                unclaimedWarningBefore,
                unclaimedWarningAfter).getSeconds())
                .isBetween(3590L, 3610L);
    }

    @Test
    void concurrentSchedulerRunsDoNotDoubleProcessOneDueCase() throws Exception {
        UUID caseId = createCase("WAITING_FOR_CUSTOMER", true);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(java.util.List.<Callable<Integer>>of(
                    () -> service.processDue(1),
                    () -> service.processDue(1)));
            int processed = results.get(0).get() + results.get(1).get();
            assertThat(processed).isEqualTo(1);
        }

        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?", String.class, caseId))
                .isEqualTo("NEW");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE action = 'CASE_WAITING_TIMEOUT' AND entity_id = ?",
                Integer.class,
                caseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'case.updated' AND aggregate_id = ?",
                Integer.class,
                caseId)).isEqualTo(1);
    }

    private UUID createCase(String status, boolean due) {
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Timeout customer " + UUID.randomUUID(),
                "timeout-" + UUID.randomUUID());
        UUID integrationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integrations (
                    id, provider, display_name, status, health, workspace_external_id
                ) VALUES (?, 'SLACK', 'Timeout Slack', 'ENABLED', 'HEALTHY', ?)
                """, integrationId, "T-" + integrationId);
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, customer_id, ignored,
                    grouping_strategy, active
                ) VALUES (?, ?, ?, 'timeout', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                """, channelId, integrationId, "C-" + channelId, customerId);

        return jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status,
                    owner_user_id, waiting_until, resolved_at, version
                ) VALUES (
                    ?, ?, ?, 'SLACK', ?, ?, ?, NULL,
                    CASE
                        WHEN ? = 'WAITING_FOR_CUSTOMER'
                            THEN CURRENT_TIMESTAMP + CASE WHEN ? THEN INTERVAL '-1 second' ELSE INTERVAL '1 hour' END
                        ELSE NULL
                    END,
                    CASE WHEN ? = 'RESOLVED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    1
                )
                RETURNING id
                """, UUID.class,
                customerId,
                integrationId,
                channelId,
                "conversation-" + UUID.randomUUID(),
                "thread-" + UUID.randomUUID(),
                status,
                status,
                due,
                status);
    }

    private void seedCaseSla(UUID caseId) {
        jdbc.update("""
                INSERT INTO case_sla (
                    case_id, policy_id,
                    first_response_started_at, first_response_due_at,
                    unclaimed_started_at, unclaimed_warning_at, unclaimed_breach_at,
                    in_progress_started_at, in_progress_warning_at, in_progress_breach_at
                )
                SELECT ?, id,
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour',
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '15 minutes',
                       CURRENT_TIMESTAMP + INTERVAL '1 hour',
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 minutes',
                       CURRENT_TIMESTAMP + INTERVAL '2 hours'
                FROM sla_policies
                WHERE active = TRUE
                """, caseId);
    }
}
