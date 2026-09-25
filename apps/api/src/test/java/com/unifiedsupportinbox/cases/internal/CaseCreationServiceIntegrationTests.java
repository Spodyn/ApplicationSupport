package com.unifiedsupportinbox.cases.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.cases.CaseCreationRejectedException;
import com.unifiedsupportinbox.cases.CaseCreationRejectedException.Reason;
import com.unifiedsupportinbox.cases.CaseCreationService;
import com.unifiedsupportinbox.cases.CaseCreationService.Command;
import com.unifiedsupportinbox.cases.CaseCreationService.Result;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class CaseCreationServiceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseCreationService service;
    private static ObjectMapper json;

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
        service = context.getBean(CaseCreationService.class);
        json = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void clearBusinessRows() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM cases");
    }

    @Test
    void createsOneNewCaseAndOneDurableCaseCreatedEventIdempotently() throws Exception {
        Fixture fixture = fixture(true, false, true);
        Command firstCommand = command(fixture, "C-support", "1710000000.000001");

        Result first = service.create(firstCommand);
        Result duplicate = service.create(new Command(
                UUID.randomUUID(),
                fixture.integrationId(),
                fixture.channelId(),
                IntegrationProvider.SLACK,
                "C-support",
                "1710000000.000001",
                "duplicate-" + UUID.randomUUID()));

        assertThat(first.created()).isTrue();
        assertThat(first.reference()).matches("CASE-[0-9]{8,}");
        assertThat(first.customerId()).isEqualTo(fixture.customerId());
        assertThat(first.status().name()).isEqualTo("NEW");
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.caseId()).isEqualTo(first.caseId());
        assertThat(duplicate.reference()).isEqualTo(first.reference());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'case.created' AND aggregate_id = ?",
                Integer.class,
                first.caseId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_sla WHERE case_id = ? AND first_response_due_at > first_response_started_at "
                        + "AND unclaimed_breach_at > unclaimed_warning_at",
                Integer.class,
                first.caseId())).isEqualTo(1);

        String payloadJson = jdbc.queryForObject(
                "SELECT payload_json::text FROM outbox_events WHERE type = 'case.created' AND aggregate_id = ?",
                String.class,
                first.caseId());
        JsonNode payload = json.readTree(payloadJson);
        assertThat(payload.get("caseId").stringValue()).isEqualTo(first.caseId().toString());
        assertThat(payload.get("reference").stringValue()).isEqualTo(first.reference());
        assertThat(payload.get("customerId").stringValue()).isEqualTo(fixture.customerId().toString());
        assertThat(payload.get("sourceInboundEventId").stringValue())
                .isEqualTo(firstCommand.sourceInboundEventId().toString());
    }

    @Test
    void rejectsChannelsThatCannotCreateCases() {
        Fixture unmapped = fixture(false, false, true);
        assertRejected(command(unmapped, "unmapped", "root-unmapped"), Reason.CHANNEL_CUSTOMER_UNMAPPED);

        Fixture ignored = fixture(true, true, true);
        assertRejected(command(ignored, "ignored", "root-ignored"), Reason.CHANNEL_IGNORED);

        Fixture inactive = fixture(true, false, false);
        assertRejected(command(inactive, "inactive", "root-inactive"), Reason.CHANNEL_INACTIVE);

        Fixture valid = fixture(true, false, true);
        Command wrongIntegration = new Command(
                UUID.randomUUID(),
                UUID.randomUUID(),
                valid.channelId(),
                IntegrationProvider.SLACK,
                "mismatch",
                "root-mismatch",
                "corr-" + UUID.randomUUID());
        assertRejected(wrongIntegration, Reason.INTEGRATION_MISMATCH);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void rollsBackCaseWhenCaseCreatedOutboxAppendFails() {
        Fixture fixture = fixture(true, false, true);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_case_created_for_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.type = 'case.created' THEN
                        RAISE EXCEPTION 'forced CaseCreated failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_case_created_for_test_trigger
                BEFORE INSERT ON outbox_events
                FOR EACH ROW
                EXECUTE FUNCTION reject_case_created_for_test()
                """);

        try {
            assertThatThrownBy(() -> service.create(command(fixture, "rollback", "root-rollback")))
                    .isInstanceOf(RuntimeException.class);

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM cases WHERE external_conversation_id = 'rollback'",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE type = 'case.created'",
                    Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_case_created_for_test_trigger ON outbox_events");
            jdbc.execute("DROP FUNCTION IF EXISTS reject_case_created_for_test()");
        }
    }

    @Test
    void concurrentDuplicateCreatesConvergeOnOneCaseAndOneEvent() throws Exception {
        Fixture fixture = fixture(true, false, true);
        int contenders = 12;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> attempts = new ArrayList<>();

        try {
            for (int index = 0; index < contenders; index++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Case creation contenders did not start together.");
                    }
                    return service.create(command(fixture, "race-conversation", "race-root"));
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<UUID> caseIds = new HashSet<>();
            int createdCount = 0;
            for (Future<Result> attempt : attempts) {
                Result result = attempt.get(20, TimeUnit.SECONDS);
                caseIds.add(result.caseId());
                if (result.created()) createdCount++;
            }

            assertThat(caseIds).hasSize(1);
            assertThat(createdCount).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM cases
                    WHERE integration_id = ?
                      AND channel_id = ?
                      AND provider = 'SLACK'
                      AND external_conversation_id = 'race-conversation'
                      AND external_thread_key = 'race-root'
                    """, Integer.class, fixture.integrationId(), fixture.channelId())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE type = 'case.created'",
                    Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void assertRejected(Command command, Reason expected) {
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOfSatisfying(
                        CaseCreationRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(expected));
    }

    private static Command command(Fixture fixture, String conversation, String thread) {
        return new Command(
                UUID.randomUUID(),
                fixture.integrationId(),
                fixture.channelId(),
                IntegrationProvider.SLACK,
                conversation,
                thread,
                "corr-" + UUID.randomUUID());
    }

    private static Fixture fixture(boolean mapped, boolean ignored, boolean active) {
        String suffix = UUID.randomUUID().toString();
        UUID customerId = mapped
                ? jdbc.queryForObject(
                        "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                        UUID.class,
                        "Customer " + suffix,
                        "customer-" + suffix)
                : null;
        UUID integrationId = jdbc.queryForObject("""
                INSERT INTO integrations (
                    provider, display_name, status, health, workspace_external_id
                ) VALUES ('SLACK', ?, 'ENABLED', 'HEALTHY', ?)
                RETURNING id
                """,
                UUID.class,
                "Slack " + suffix,
                "workspace-" + suffix);
        UUID channelId = jdbc.queryForObject("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, ?, ?, ?, 'SLACK_ROOT_THREAD', ?)
                RETURNING id
                """,
                UUID.class,
                integrationId,
                "channel-" + suffix,
                "support-" + suffix,
                customerId,
                ignored,
                active);
        return new Fixture(customerId, integrationId, channelId);
    }

    private record Fixture(UUID customerId, UUID integrationId, UUID channelId) {
    }
}
