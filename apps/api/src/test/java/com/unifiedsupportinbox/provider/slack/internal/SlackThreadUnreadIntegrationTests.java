package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.testing.TestInfrastructure;
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
class SlackThreadUnreadIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Autowired private SlackInboundDeliveryService deliveries;
    @Autowired private SlackInboundWorker worker;
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
    void customerThreadReplyMakesPreviouslyReadCaseUnreadWithoutMovingReadCursor() {
        UUID viewer = createUser();
        Fixture fixture = mappedFixture("C-thread-unread");
        String rootTs = "1722000000.100001";
        String replyTs = "1722000000.200002";

        InboundEvent root = persist(
                fixture.integrationId(),
                "Ev-thread-unread-root",
                rootMessage(
                        "Ev-thread-unread-root",
                        fixture.externalChannelId(),
                        "U-customer",
                        "initial request",
                        rootTs));

        assertThat(worker.process(root.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);

        UUID caseId = jdbc.queryForObject("SELECT id FROM cases", UUID.class);
        UUID rootMessageId = jdbc.queryForObject(
                "SELECT id FROM messages WHERE external_message_id = ?",
                UUID.class,
                rootTs);
        assertThat(readStateCount(viewer, caseId)).isEqualTo(1);
        assertThat(countOutbox("case.unread_changed")).isEqualTo(1);

        jdbc.update("""
                UPDATE case_read_states
                SET last_read_message_id = ?,
                    last_read_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND case_id = ?
                """, rootMessageId, viewer, caseId);

        InboundEvent reply = persist(
                fixture.integrationId(),
                "Ev-thread-unread-reply",
                threadReply(
                        "Ev-thread-unread-reply",
                        fixture.externalChannelId(),
                        "U-customer",
                        "customer follow-up",
                        replyTs,
                        rootTs));

        assertThat(worker.process(reply.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);

        UUID replyMessageId = jdbc.queryForObject(
                "SELECT id FROM messages WHERE external_message_id = ?",
                UUID.class,
                replyTs);
        assertThat(replyMessageId).isNotEqualTo(rootMessageId);
        assertThat(jdbc.queryForObject(
                "SELECT case_id FROM messages WHERE id = ?",
                UUID.class,
                replyMessageId)).isEqualTo(caseId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT last_read_message_id
                FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, UUID.class, viewer, caseId)).isEqualTo(rootMessageId);
        assertThat(countOutbox("case.unread_changed")).isEqualTo(2);
    }

    private UUID createUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, 'Viewer', 'USER', TRUE)
                RETURNING id
                """, UUID.class, "viewer-" + suffix + "@example.invalid");
    }

    private Fixture mappedFixture(String externalChannelId) {
        String suffix = UUID.randomUUID().toString();
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Slack customer " + suffix,
                "slack-customer-" + suffix);
        UUID integrationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integrations (
                    id, provider, display_name, status, health, workspace_external_id
                ) VALUES (?, 'SLACK', 'Test Slack', 'ENABLED', 'HEALTHY', ?)
                """, integrationId, "T-" + integrationId);
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                """, channelId, integrationId, externalChannelId, customerId);
        return new Fixture(integrationId, externalChannelId);
    }

    private InboundEvent persist(UUID integrationId, String eventId, String payload) {
        return deliveries.persistAndWake(integrationId, eventId, payload, "corr-" + eventId);
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

    private int readStateCount(UUID userId, UUID caseId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, Integer.class, userId, caseId);
    }

    private int countOutbox(String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = ?",
                Integer.class,
                type);
    }

    private record Fixture(UUID integrationId, String externalChannelId) {
    }
}
