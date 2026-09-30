package com.unifiedsupportinbox.readstate.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class CaseSnoozeDueWorkerIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseSnoozeDueWorker worker;

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
                        "--usi.bootstrap-admin.enabled=false",
                        "--usi.readstate.snooze-worker.enabled=true",
                        "--usi.readstate.snooze-worker.poll-interval=1h",
                        "--usi.readstate.snooze-worker.initial-delay=1h");
        jdbc = context.getBean(JdbcTemplate.class);
        worker = context.getBean(CaseSnoozeDueWorker.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        TestInfrastructure.resetPostgres(POSTGRES);
    }

    @Test
    void dueSnoozeIsDeletedAndEmitsExactlyOnePrivateWakeEvent() {
        Fixture fixture = fixture();
        UUID userId = createUser();
        Instant createdAt = Instant.now().minusSeconds(600);
        Instant dueAt = Instant.now().minusSeconds(60);
        jdbc.update("""
                INSERT INTO case_snoozes (case_id, user_id, until_at, created_at)
                VALUES (?, ?, ?, ?)
                """, fixture.caseId(), userId, dueAt, createdAt);

        worker.wakeDue();
        worker.wakeDue();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_snoozes WHERE case_id = ? AND user_id = ?",
                Integer.class, fixture.caseId(), userId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE type = 'case.snooze_due' AND aggregate_id = ?
                """, Integer.class, fixture.caseId())).isEqualTo(1);
        String payload = jdbc.queryForObject("""
                SELECT payload_json::text FROM outbox_events
                WHERE type = 'case.snooze_due' AND aggregate_id = ?
                """, String.class, fixture.caseId());
        assertThat(payload)
                .contains(fixture.caseId().toString())
                .contains(userId.toString())
                .contains("snoozedUntil")
                .contains("dueAt");
        assertThat(jdbc.queryForObject("SELECT status FROM cases WHERE id = ?", String.class, fixture.caseId()))
                .isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT version FROM cases WHERE id = ?", Long.class, fixture.caseId()))
                .isZero();
    }

    @Test
    void futureSnoozeIsNotWokenEarly() {
        Fixture fixture = fixture();
        UUID userId = createUser();
        jdbc.update("""
                INSERT INTO case_snoozes (case_id, user_id, until_at, created_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP)
                """, fixture.caseId(), userId);

        worker.wakeDue();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_snoozes WHERE case_id = ? AND user_id = ?",
                Integer.class, fixture.caseId(), userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'case.snooze_due'",
                Integer.class)).isZero();
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class, "Customer " + suffix, "customer-" + suffix);
        UUID integrationId = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', ?, 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, "Slack " + suffix, "workspace-" + suffix);
        UUID channelId = jdbc.queryForObject("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                RETURNING id
                """, UUID.class, integrationId, "channel-" + suffix, customerId);
        UUID caseId = jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status
                ) VALUES (?, ?, ?, 'SLACK', ?, ?, 'NEW') RETURNING id
                """, UUID.class, customerId, integrationId, channelId,
                "conversation-" + suffix, "thread-" + suffix);
        return new Fixture(caseId);
    }

    private static UUID createUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, 'Snooze User', 'USER', TRUE) RETURNING id
                """, UUID.class, "snooze-" + suffix + "@example.invalid");
    }

    private record Fixture(UUID caseId) {}
}
