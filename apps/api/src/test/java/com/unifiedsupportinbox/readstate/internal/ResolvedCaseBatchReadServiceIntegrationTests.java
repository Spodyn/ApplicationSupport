package com.unifiedsupportinbox.readstate.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.sql.Timestamp;
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
class ResolvedCaseBatchReadServiceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ResolvedCaseBatchReadService service;

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
        service = context.getBean(ResolvedCaseBatchReadService.class);
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
    void marksOnlyCurrentUsersUnreadResolvedCases() {
        BaseFixture base = baseFixture();
        UUID actor = createUser("actor");
        UUID other = createUser("other");

        UUID firstResolved = createCase(base, "resolved-one", "RESOLVED");
        UUID firstOld = message(firstResolved, "first-old", Instant.parse("2026-09-30T06:00:00Z"));
        UUID firstLatest = message(firstResolved, "first-latest", Instant.parse("2026-09-30T06:01:00Z"));
        readState(actor, firstResolved, firstOld);
        readState(other, firstResolved, firstOld);

        UUID secondResolved = createCase(base, "resolved-two", "RESOLVED");
        UUID secondLatest = message(secondResolved, "second-latest", Instant.parse("2026-09-30T06:02:00Z"));
        readState(actor, secondResolved, null);

        UUID active = createCase(base, "active", "NEW");
        message(active, "active-latest", Instant.parse("2026-09-30T06:03:00Z"));
        readState(actor, active, null);

        UUID ignored = createCase(base, "ignored", "IGNORED");
        message(ignored, "ignored-latest", Instant.parse("2026-09-30T06:04:00Z"));
        readState(actor, ignored, null);

        var result = service.markResolvedRead(actor, "batch-one");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("updatedCount").intValue()).isEqualTo(2);
        assertThat(cursor(actor, firstResolved)).isEqualTo(firstLatest);
        assertThat(cursor(actor, secondResolved)).isEqualTo(secondLatest);
        assertThat(cursor(other, firstResolved)).isEqualTo(firstOld);
        assertThat(cursor(actor, active)).isNull();
        assertThat(cursor(actor, ignored)).isNull();
    }

    @Test
    void absentReadStateDoesNotTurnHistoricResolvedCaseIntoCurrentUsersReadState() {
        BaseFixture base = baseFixture();
        UUID actor = createUser("late-user");
        UUID historic = createCase(base, "historic", "RESOLVED");
        message(historic, "historic-latest", Instant.parse("2026-09-30T06:10:00Z"));

        var result = service.markResolvedRead(actor, "historic-batch");

        assertThat(result.body().get("updatedCount").intValue()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, Integer.class, actor, historic)).isZero();
    }

    @Test
    void emptyAndRepeatedCommandsAreSafe() {
        BaseFixture base = baseFixture();
        UUID actor = createUser("repeat");
        UUID resolved = createCase(base, "already-read", "RESOLVED");
        UUID latest = message(resolved, "already-latest", Instant.parse("2026-09-30T06:20:00Z"));
        readState(actor, resolved, latest);

        var first = service.markResolvedRead(actor, "empty-key");
        var replay = service.markResolvedRead(actor, "empty-key");

        assertThat(first.body().get("updatedCount").intValue()).isZero();
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().get("updatedCount").intValue()).isZero();
        assertThat(cursor(actor, resolved)).isEqualTo(latest);
    }

    @Test
    void idempotentReplayDoesNotConsumeCustomerMessageCommittedAfterOriginalBatch() {
        BaseFixture base = baseFixture();
        UUID actor = createUser("race");
        UUID resolved = createCase(base, "race", "RESOLVED");
        UUID first = message(resolved, "race-first", Instant.parse("2026-09-30T06:30:00Z"));
        readState(actor, resolved, null);

        var initial = service.markResolvedRead(actor, "race-key");
        assertThat(initial.body().get("updatedCount").intValue()).isEqualTo(1);
        assertThat(cursor(actor, resolved)).isEqualTo(first);

        UUID newer = message(resolved, "race-newer", Instant.parse("2026-09-30T06:31:00Z"));
        var replay = service.markResolvedRead(actor, "race-key");

        assertThat(replay.replayed()).isTrue();
        assertThat(cursor(actor, resolved)).isEqualTo(first);

        var freshCommand = service.markResolvedRead(actor, "race-key-2");
        assertThat(freshCommand.body().get("updatedCount").intValue()).isEqualTo(1);
        assertThat(cursor(actor, resolved)).isEqualTo(newer);
    }

    private static BaseFixture baseFixture() {
        String suffix = UUID.randomUUID().toString();
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Batch customer " + suffix,
                "batch-customer-" + suffix);
        UUID integrationId = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', ?, 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, "Batch Slack " + suffix, "workspace-" + suffix);
        UUID channelId = jdbc.queryForObject("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                RETURNING id
                """, UUID.class, integrationId, "channel-" + suffix, customerId);
        return new BaseFixture(customerId, integrationId, channelId);
    }

    private static UUID createUser(String label) {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, ?, 'USER', TRUE) RETURNING id
                """, UUID.class, label + "-" + suffix + "@example.invalid", "User " + label);
    }

    private static UUID createCase(BaseFixture base, String label, String status) {
        String suffix = label + "-" + UUID.randomUUID();
        UUID caseId = jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status
                ) VALUES (?, ?, ?, 'SLACK', ?, ?, 'NEW') RETURNING id
                """, UUID.class,
                base.customerId(), base.integrationId(), base.channelId(),
                "conversation-" + suffix, "thread-" + suffix);
        if ("RESOLVED".equals(status)) {
            jdbc.update(
                    "UPDATE cases SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE id = ?",
                    caseId);
        } else if ("IGNORED".equals(status)) {
            jdbc.update(
                    "UPDATE cases SET status = 'IGNORED', ignored_at = CURRENT_TIMESTAMP WHERE id = ?",
                    caseId);
        }
        return caseId;
    }

    private static UUID message(UUID caseId, String externalId, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO messages (
                    case_id, external_message_id, external_thread_key, kind,
                    author_external_id, body, body_format, inbound,
                    provider_created_at, correlation_id
                ) VALUES (?, ?, 'thread', 'CUSTOMER', 'U-customer', ?,
                          'PLAIN_TEXT', TRUE, ?, 'corr-batch') RETURNING id
                """, UUID.class,
                caseId,
                externalId + "-" + UUID.randomUUID(),
                externalId,
                Timestamp.from(createdAt));
    }

    private static void readState(UUID userId, UUID caseId, UUID messageId) {
        jdbc.update("""
                INSERT INTO case_read_states (
                    user_id, case_id, last_read_message_id, last_read_at, updated_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                userId,
                caseId,
                messageId,
                messageId == null ? null : Timestamp.from(Instant.parse("2026-09-30T06:00:00Z")));
    }

    private static UUID cursor(UUID userId, UUID caseId) {
        return jdbc.queryForObject("""
                SELECT last_read_message_id FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, UUID.class, userId, caseId);
    }

    private record BaseFixture(UUID customerId, UUID integrationId, UUID channelId) {}
}
