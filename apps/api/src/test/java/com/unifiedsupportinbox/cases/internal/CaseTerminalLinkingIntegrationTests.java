package com.unifiedsupportinbox.cases.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
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
class CaseTerminalLinkingIntegrationTests {

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
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM cases");
    }

    @Test
    void resolvedCaseIsNeverReopenedAndNextCaseLinksToIt() throws Exception {
        Fixture fixture = fixture();
        Result original = service.create(command(fixture, "thread-resolved"));
        jdbc.update(
                "UPDATE cases SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP, resolution_category = 'SOLVED' WHERE id = ?",
                original.caseId());

        Result successor = service.create(command(fixture, "thread-resolved"));

        assertThat(successor.created()).isTrue();
        assertThat(successor.caseId()).isNotEqualTo(original.caseId());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?", String.class, original.caseId()))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject(
                "SELECT related_case_id FROM cases WHERE id = ?", UUID.class, successor.caseId()))
                .isEqualTo(original.caseId());
        assertCaseCreatedPayloadLinks(successor.caseId(), original.caseId());
    }

    @Test
    void ignoredCaseIsNeverReopenedAndNextCaseLinksToIt() {
        Fixture fixture = fixture();
        Result original = service.create(command(fixture, "thread-ignored"));
        jdbc.update(
                "UPDATE cases SET status = 'IGNORED', ignored_at = CURRENT_TIMESTAMP, resolution_category = 'NOT_ACTIONABLE' WHERE id = ?",
                original.caseId());

        Result successor = service.create(command(fixture, "thread-ignored"));

        assertThat(successor.created()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?", String.class, original.caseId()))
                .isEqualTo("IGNORED");
        assertThat(jdbc.queryForObject(
                "SELECT related_case_id FROM cases WHERE id = ?", UUID.class, successor.caseId()))
                .isEqualTo(original.caseId());
    }

    @Test
    void multipleTerminalGenerationsFormAChainToTheMostRecentCase() {
        Fixture fixture = fixture();
        Result first = service.create(command(fixture, "thread-chain"));
        resolve(first.caseId());

        Result second = service.create(command(fixture, "thread-chain"));
        resolve(second.caseId());

        Result third = service.create(command(fixture, "thread-chain"));

        assertThat(jdbc.queryForObject(
                "SELECT related_case_id FROM cases WHERE id = ?", UUID.class, second.caseId()))
                .isEqualTo(first.caseId());
        assertThat(jdbc.queryForObject(
                "SELECT related_case_id FROM cases WHERE id = ?", UUID.class, third.caseId()))
                .isEqualTo(second.caseId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cases WHERE external_thread_key = 'thread-chain'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void concurrentMessagesAfterTerminalCaseConvergeOnOneLinkedActiveSuccessor() throws Exception {
        Fixture fixture = fixture();
        Result original = service.create(command(fixture, "thread-race-terminal"));
        resolve(original.caseId());
        jdbc.update("DELETE FROM outbox_events");

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
                        throw new IllegalStateException("Terminal successor contenders did not start together.");
                    }
                    return service.create(command(fixture, "thread-race-terminal"));
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<UUID> successorIds = new HashSet<>();
            int created = 0;
            for (Future<Result> attempt : attempts) {
                Result result = attempt.get(20, TimeUnit.SECONDS);
                successorIds.add(result.caseId());
                if (result.created()) created++;
            }

            assertThat(successorIds).hasSize(1);
            assertThat(created).isEqualTo(1);
            UUID successorId = successorIds.iterator().next();
            assertThat(successorId).isNotEqualTo(original.caseId());
            assertThat(jdbc.queryForObject(
                    "SELECT related_case_id FROM cases WHERE id = ?", UUID.class, successorId))
                    .isEqualTo(original.caseId());
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM cases
                    WHERE external_thread_key = 'thread-race-terminal'
                      AND status NOT IN ('RESOLVED', 'IGNORED')
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE type = 'case.created'",
                    Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertCaseCreatedPayloadLinks(UUID caseId, UUID relatedCaseId) throws Exception {
        String payloadJson = jdbc.queryForObject(
                "SELECT payload_json::text FROM outbox_events WHERE type = 'case.created' AND aggregate_id = ?",
                String.class,
                caseId);
        JsonNode payload = json.readTree(payloadJson);
        assertThat(payload.get("relatedCaseId").stringValue()).isEqualTo(relatedCaseId.toString());
    }

    private static void resolve(UUID caseId) {
        jdbc.update(
                "UPDATE cases SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP, resolution_category = 'SOLVED' WHERE id = ?",
                caseId);
    }

    private static Command command(Fixture fixture, String thread) {
        return new Command(
                UUID.randomUUID(),
                fixture.integrationId(),
                fixture.channelId(),
                IntegrationProvider.SLACK,
                fixture.externalChannelId(),
                thread,
                "corr-" + UUID.randomUUID());
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Customer " + suffix,
                "customer-" + suffix);
        UUID integrationId = jdbc.queryForObject("""
                INSERT INTO integrations (
                    provider, display_name, status, health, workspace_external_id
                ) VALUES ('SLACK', ?, 'ENABLED', 'HEALTHY', ?)
                RETURNING id
                """,
                UUID.class,
                "Slack " + suffix,
                "workspace-" + suffix);
        String externalChannelId = "channel-" + suffix;
        UUID channelId = jdbc.queryForObject("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, ?, ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                RETURNING id
                """,
                UUID.class,
                integrationId,
                externalChannelId,
                "support-" + suffix,
                customerId);
        return new Fixture(integrationId, channelId, externalChannelId);
    }

    private record Fixture(UUID integrationId, UUID channelId, String externalChannelId) {
    }
}
