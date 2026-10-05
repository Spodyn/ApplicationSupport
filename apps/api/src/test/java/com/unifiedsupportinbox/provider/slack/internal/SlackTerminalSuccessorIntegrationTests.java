package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "usi.slack.inbound-worker.enabled=false")
@ActiveProfiles("test")
class SlackTerminalSuccessorIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Autowired
    private SlackInboundDeliveryService deliveries;

    @Autowired
    private SlackInboundWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

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
    void replyAfterTerminalCaseCreatesLinkedSuccessorAndKeepsTerminalCaseImmutable() {
        Fixture fixture = mappedFixture("C-terminal-successor");
        String rootTs = "1721000000.100001";
        String successorTs = "1721000000.200002";
        String followUpTs = "1721000000.300003";

        InboundEvent root = persist(
                fixture.integrationId(),
                "Ev-terminal-root",
                rootMessage(
                        "Ev-terminal-root",
                        fixture.externalChannelId(),
                        "U-customer",
                        "first generation",
                        rootTs));

        assertThat(worker.process(root.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);

        UUID originalCaseId = jdbc.queryForObject("SELECT id FROM cases", UUID.class);
        jdbc.update(
                "UPDATE cases SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE id = ?",
                originalCaseId);

        InboundEvent successorReply = persist(
                fixture.integrationId(),
                "Ev-terminal-successor",
                threadReply(
                        "Ev-terminal-successor",
                        fixture.externalChannelId(),
                        "U-customer",
                        "customer came back after resolution",
                        successorTs,
                        rootTs));

        assertThat(worker.process(successorReply.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);

        List<Map<String, Object>> cases = jdbc.queryForList("""
                SELECT id, status, related_case_id, external_thread_key, resolved_at
                FROM cases
                ORDER BY created_at, id
                """);
        assertThat(cases).hasSize(2);

        Map<String, Object> original = cases.stream()
                .filter(row -> originalCaseId.equals(row.get("id")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> successor = cases.stream()
                .filter(row -> !originalCaseId.equals(row.get("id")))
                .findFirst()
                .orElseThrow();
        UUID successorCaseId = (UUID) successor.get("id");

        assertThat(original.get("status")).isEqualTo("RESOLVED");
        assertThat(original.get("resolved_at")).isNotNull();
        assertThat(original.get("related_case_id")).isNull();
        assertThat(original.get("external_thread_key")).isEqualTo(rootTs);

        assertThat(successor.get("status")).isEqualTo("NEW");
        assertThat(successor.get("related_case_id")).isEqualTo(originalCaseId);
        assertThat(successor.get("external_thread_key")).isEqualTo(rootTs);
        assertThat(successor.get("resolved_at")).isNull();

        assertThat(jdbc.queryForObject(
                "SELECT case_id FROM messages WHERE external_message_id = ?",
                UUID.class,
                rootTs)).isEqualTo(originalCaseId);
        assertThat(jdbc.queryForObject(
                "SELECT case_id FROM messages WHERE external_message_id = ?",
                UUID.class,
                successorTs)).isEqualTo(successorCaseId);
        assertThat(countOutbox("case.created")).isEqualTo(2);

        InboundEvent followUp = persist(
                fixture.integrationId(),
                "Ev-terminal-follow-up",
                threadReply(
                        "Ev-terminal-follow-up",
                        fixture.externalChannelId(),
                        "U-customer",
                        "same successor generation",
                        followUpTs,
                        rootTs));

        assertThat(worker.process(followUp.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT case_id FROM messages WHERE external_message_id = ?",
                UUID.class,
                followUpTs)).isEqualTo(successorCaseId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?",
                String.class,
                originalCaseId)).isEqualTo("RESOLVED");
        assertThat(countOutbox("case.created")).isEqualTo(2);
    }

    private Fixture mappedFixture(String externalChannelId) {
        UUID customerId = createCustomer();
        UUID integrationId = createSlackIntegration();
        UUID channelId = createChannel(integrationId, externalChannelId, customerId);
        return new Fixture(integrationId, channelId, externalChannelId);
    }

    private UUID createCustomer() {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Slack customer " + suffix,
                "slack-customer-" + suffix);
    }

    private UUID createSlackIntegration() {
        UUID integrationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integrations (
                    id, provider, display_name, status, health, workspace_external_id
                ) VALUES (?, 'SLACK', 'Test Slack', 'ENABLED', 'HEALTHY', ?)
                """, integrationId, "T-" + integrationId);
        return integrationId;
    }

    private UUID createChannel(UUID integrationId, String externalChannelId, UUID customerId) {
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, customer_id, ignored,
                    grouping_strategy, active
                ) VALUES (?, ?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                """, channelId, integrationId, externalChannelId, customerId);
        return channelId;
    }

    private InboundEvent persist(UUID integrationId, String eventId, String payload) {
        return deliveries.persistAndWake(integrationId, eventId, payload.strip(), "corr-" + eventId);
    }

    private static String rootMessage(
            String eventId,
            String channel,
            String user,
            String text,
            String ts) {
        return "{\"type\":\"event_callback\",\"event_id\":\"" + eventId
                + "\",\"event\":{\"type\":\"message\",\"channel\":\"" + channel
                + "\",\"user\":\"" + user + "\",\"text\":\"" + text
                + "\",\"ts\":\"" + ts + "\"}}";
    }

    private static String threadReply(
            String eventId,
            String channel,
            String user,
            String text,
            String ts,
            String threadTs) {
        return "{\"type\":\"event_callback\",\"event_id\":\"" + eventId
                + "\",\"event\":{\"type\":\"message\",\"channel\":\"" + channel
                + "\",\"user\":\"" + user + "\",\"text\":\"" + text
                + "\",\"ts\":\"" + ts + "\",\"thread_ts\":\"" + threadTs + "\"}}";
    }

    private int countOutbox(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE type = ?", Integer.class, type);
    }

    private record Fixture(UUID integrationId, UUID channelId, String externalChannelId) {
    }
}
