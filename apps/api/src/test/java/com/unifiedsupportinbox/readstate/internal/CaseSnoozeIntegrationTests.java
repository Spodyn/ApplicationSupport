package com.unifiedsupportinbox.readstate.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.testing.PersistenceFixtures;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class CaseSnoozeIntegrationTests {
    private static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static Instant now;
    @BeforeAll static void start() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class).profiles("test").run(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(), "--spring.flyway.enabled=true",
                "--spring.jpa.hibernate.ddl-auto=validate", "--usi.readstate.snooze-worker.enabled=false",
                "--management.otlp.metrics.export.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class); now = Instant.now();
    }
    @AfterAll static void stop() { if (context != null) context.close(); POSTGRES.stop(); }

    @Test void completedSnoozeReplayRemainsIdempotentAfterTheOriginalDeadline() {
        var fixture = fixture(); String key = UUID.randomUUID().toString();
        var original = service(Clock.fixed(now, ZoneOffset.UTC)).snooze(fixture.caseId(), fixture.userId(), now.plusSeconds(600), key, "test-only-snooze");
        var replay = service(Clock.fixed(now.plusSeconds(1200), ZoneOffset.UTC)).snooze(fixture.caseId(), fixture.userId(), now.plusSeconds(600), key, "test-only-replay");
        assertThat(replay.status()).isEqualTo(original.status());
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND type = 'case.snoozed'", fixture.caseId())).isEqualTo(1);
    }

    @Test void durationLimitsUseAControlledClockAndNeverMutateWorkflowOwnership() {
        var fixture = fixture(); var snoozes = service(Clock.fixed(now, ZoneOffset.UTC));
        for (Instant invalid : new Instant[]{now.minusSeconds(1), now.plusSeconds(299), now.plus(Duration.ofDays(30)).plusNanos(1)}) {
            assertThatThrownBy(() -> snoozes.snooze(fixture.caseId(), fixture.userId(), invalid, UUID.randomUUID().toString(), "test-only-boundary"))
                    .isInstanceOf(ApiProblemException.class);
        }
        assertThat(snoozes.snooze(fixture.caseId(), fixture.userId(), now.plusSeconds(300), UUID.randomUUID().toString(), "test-only-min").status()).isEqualTo(200);
        assertThat(snoozes.snooze(fixture.caseId(), fixture.userId(), now.plus(Duration.ofDays(30)), UUID.randomUUID().toString(), "test-only-max").status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM cases WHERE id = ?", String.class, fixture.caseId())).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM cases WHERE id = ?", UUID.class, fixture.caseId())).isNull();
        assertThat(count("SELECT count(*) FROM case_read_states WHERE user_id = ?", fixture.userId())).isEqualTo(1);
    }

    @Test void cancellationAndDueProcessingArePersonalAndEmitEachEffectOnlyOnce() {
        var fixture = fixture(); var other = new PersistenceFixtures(jdbc).user().id();
        var snoozes = service(Clock.fixed(now, ZoneOffset.UTC));
        snoozes.snooze(fixture.caseId(), fixture.userId(), now.plusSeconds(600), UUID.randomUUID().toString(), "test-only-first");
        snoozes.snooze(fixture.caseId(), other, now.plusSeconds(900), UUID.randomUUID().toString(), "test-only-second");
        String key = UUID.randomUUID().toString();
        snoozes.cancel(fixture.caseId(), fixture.userId(), key, "test-only-cancel");
        snoozes.cancel(fixture.caseId(), fixture.userId(), key, "test-only-cancel-replay");
        assertThat(count("SELECT count(*) FROM case_snoozes WHERE user_id = ?", other)).isEqualTo(1);
        jdbc.update("UPDATE case_snoozes SET created_at = now() - interval '2 hour', until_at = now() - interval '1 hour' WHERE user_id = ?", other);
        var worker = new CaseSnoozeDueWorker(jdbc, context.getBean(OutboxEventStore.class), context.getBean(ObjectMapper.class));
        var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transactions.executeWithoutResult(status -> worker.wakeDue());
        transactions.executeWithoutResult(status -> worker.wakeDue());
        assertThat(count("SELECT count(*) FROM case_snoozes WHERE case_id = ?", fixture.caseId())).isZero();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND type = 'case.snooze_due'", fixture.caseId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND type = 'case.snooze_cancelled'", fixture.caseId())).isEqualTo(1);
    }

    private static CaseSnoozeService service(Clock clock) {
        return new CaseSnoozeService(jdbc, context.getBean(IdempotentCommandExecutor.class), context.getBean(CaseReadPositionService.class),
                context.getBean(OutboxEventStore.class), context.getBean(ObjectMapper.class), clock);
    }
    private static Fixture fixture() {
        var data = new PersistenceFixtures(jdbc);
        UUID id = jdbc.queryForObject("""
                INSERT INTO cases (customer_id, integration_id, channel_id, provider, external_conversation_id, external_thread_key, status)
                VALUES (?, ?, ?, 'SLACK', ?, ?, 'NEW') RETURNING id
                """, UUID.class, data.customer().id(), data.integration().id(), data.channel().id(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        jdbc.update("INSERT INTO messages (case_id, kind, external_message_id, provider_created_at, author_external_id, body, body_format, inbound, correlation_id) VALUES (?, 'CUSTOMER', ?, now(), 'UTESTSNOOZE', 'Synthetic snooze fixture', 'PLAIN_TEXT', TRUE, 'test-only-snooze')", id, UUID.randomUUID().toString());
        return new Fixture(id, data.user().id());
    }
    private static int count(String query, UUID id) { return jdbc.queryForObject(query, Integer.class, id); }
    private record Fixture(UUID caseId, UUID userId) { }
}
