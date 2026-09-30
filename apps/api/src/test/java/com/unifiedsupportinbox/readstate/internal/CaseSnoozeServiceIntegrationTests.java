package com.unifiedsupportinbox.readstate.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemException;
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
class CaseSnoozeServiceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseSnoozeService snoozes;

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
        snoozes = context.getBean(CaseSnoozeService.class);
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
    void snoozeIsPrivateMarksActorReadAndDoesNotChangeGlobalCaseState() {
        Fixture fixture = fixture("private");
        UUID firstUser = createUser("first");
        UUID secondUser = createUser("second");
        UUID latestMessage = customerMessage(fixture.caseId(), Instant.now());
        Instant until = Instant.now().plusSeconds(3600);

        var result = snoozes.snooze(
                fixture.caseId(), firstUser, until, "snooze-private", "corr-snooze-private");

        assertThat(result.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_snoozes WHERE case_id = ? AND user_id = ?",
                Integer.class, fixture.caseId(), firstUser)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_snoozes WHERE case_id = ? AND user_id = ?",
                Integer.class, fixture.caseId(), secondUser)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT last_read_message_id FROM case_read_states WHERE case_id = ? AND user_id = ?",
                UUID.class, fixture.caseId(), firstUser)).isEqualTo(latestMessage);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_read_states WHERE case_id = ? AND user_id = ?",
                Integer.class, fixture.caseId(), secondUser)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM cases WHERE id = ?", String.class, fixture.caseId()))
                .isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM cases WHERE id = ?", UUID.class, fixture.caseId()))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT version FROM cases WHERE id = ?", Long.class, fixture.caseId()))
                .isZero();
        assertThat(countOutbox("case.snoozed", fixture.caseId())).isEqualTo(1);
    }

    @Test
    void repeatedSnoozeReplacesOnlyActorsExistingReminder() {
        Fixture fixture = fixture("repeat");
        UUID user = createUser("repeat");
        customerMessage(fixture.caseId(), Instant.now());
        Instant firstUntil = Instant.now().plusSeconds(3600);
        Instant secondUntil = Instant.now().plusSeconds(7200);

        snoozes.snooze(fixture.caseId(), user, firstUntil, "repeat-one", "corr-repeat-one");
        snoozes.snooze(fixture.caseId(), user, secondUntil, "repeat-two", "corr-repeat-two");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_snoozes WHERE case_id = ? AND user_id = ?",
                Integer.class, fixture.caseId(), user)).isEqualTo(1);
        Instant stored = jdbc.queryForObject(
                "SELECT until_at FROM case_snoozes WHERE case_id = ? AND user_id = ?",
                Timestamp.class, fixture.caseId(), user).toInstant();
        assertThat(stored).isEqualTo(secondUntil);
        assertThat(countOutbox("case.snoozed", fixture.caseId())).isEqualTo(2);
    }

    @Test
    void idempotencyReplayDoesNotCreateAnotherReminderEvent() {
        Fixture fixture = fixture("idempotent");
        UUID user = createUser("idempotent");
        customerMessage(fixture.caseId(), Instant.now());
        Instant until = Instant.now().plusSeconds(3600);

        var first = snoozes.snooze(fixture.caseId(), user, until, "same-key", "corr-first");
        var replay = snoozes.snooze(fixture.caseId(), user, until, "same-key", "corr-replay");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(countOutbox("case.snoozed", fixture.caseId())).isEqualTo(1);
    }

    @Test
    void cancelRemovesOnlyCurrentUsersSnoozeAndIsSafeWhenRepeated() {
        Fixture fixture = fixture("cancel");
        UUID firstUser = createUser("cancel-first");
        UUID secondUser = createUser("cancel-second");
        customerMessage(fixture.caseId(), Instant.now());
        Instant until = Instant.now().plusSeconds(3600);
        snoozes.snooze(fixture.caseId(), firstUser, until, "first-snooze", "corr-first-snooze");
        snoozes.snooze(fixture.caseId(), secondUser, until, "second-snooze", "corr-second-snooze");

        snoozes.cancel(fixture.caseId(), firstUser, "cancel-one", "corr-cancel-one");
        snoozes.cancel(fixture.caseId(), firstUser, "cancel-two", "corr-cancel-two");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_snoozes WHERE case_id = ? AND user_id = ?",
                Integer.class, fixture.caseId(), firstUser)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_snoozes WHERE case_id = ? AND user_id = ?",
                Integer.class, fixture.caseId(), secondUser)).isEqualTo(1);
        assertThat(countOutbox("case.snooze_cancelled", fixture.caseId())).isEqualTo(1);
    }

    @Test
    void rejectsTerminalCasesAndDurationsOutsideFrozenRange() {
        Fixture fixture = fixture("validation");
        UUID user = createUser("validation");
        customerMessage(fixture.caseId(), Instant.now());

        assertThatThrownBy(() -> snoozes.snooze(
                fixture.caseId(), user, Instant.now().plusSeconds(60), "too-short", "corr-short"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> snoozes.snooze(
                fixture.caseId(), user, Instant.now().plusSeconds(31L * 24 * 3600), "too-long", "corr-long"))
                .isInstanceOf(ApiProblemException.class);

        jdbc.update("UPDATE cases SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE id = ?", fixture.caseId());
        assertThatThrownBy(() -> snoozes.snooze(
                fixture.caseId(), user, Instant.now().plusSeconds(3600), "terminal", "corr-terminal"))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("Terminal Cases");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_snoozes", Integer.class)).isZero();
    }

    private static Fixture fixture(String label) {
        String suffix = label + "-" + UUID.randomUUID();
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

    private static UUID createUser(String label) {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, ?, 'USER', TRUE) RETURNING id
                """, UUID.class, label + "-" + suffix + "@example.invalid", "User " + label);
    }

    private static UUID customerMessage(UUID caseId, Instant providerCreatedAt) {
        return jdbc.queryForObject("""
                INSERT INTO messages (
                    case_id, external_message_id, external_thread_key, kind,
                    author_external_id, body, body_format, inbound,
                    provider_created_at, correlation_id
                ) VALUES (?, ?, 'thread', 'CUSTOMER', 'U-customer', 'hello',
                          'PLAIN_TEXT', TRUE, ?, 'corr-message') RETURNING id
                """, UUID.class, caseId, "provider-" + UUID.randomUUID(), Timestamp.from(providerCreatedAt));
    }

    private static int countOutbox(String type, UUID caseId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = ? AND aggregate_id = ?",
                Integer.class, type, caseId);
    }

    private record Fixture(UUID caseId) {}
}
