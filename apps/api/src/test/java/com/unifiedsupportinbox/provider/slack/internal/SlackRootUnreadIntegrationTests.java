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
class SlackRootUnreadIntegrationTests {

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
    void realSlackRootMessageCreatesUnreadOnceAndDuplicateDoesNotBackfillLaterUser() {
        UUID viewer = createUser("viewer", true);
        Fixture fixture = mappedFixture("C-unread-dedup");
        String ts = "1721000000.000001";
        InboundEvent first = persist(
                fixture.integrationId(),
                "Ev-unread-a",
                rootMessage("Ev-unread-a", fixture.externalChannelId(), ts));
        InboundEvent duplicate = persist(
                fixture.integrationId(),
                "Ev-unread-b",
                rootMessage("Ev-unread-b", fixture.externalChannelId(), ts));

        assertThat(worker.process(first.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        UUID caseId = jdbc.queryForObject("SELECT id FROM cases", UUID.class);
        assertThat(readStateCount(viewer, caseId)).isEqualTo(1);
        assertThat(countOutbox("case.unread_changed")).isEqualTo(1);

        UUID laterViewer = createUser("later-viewer", true);
        assertThat(worker.process(duplicate.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);

        assertThat(readStateCount(viewer, caseId)).isEqualTo(1);
        assertThat(readStateCount(laterViewer, caseId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isEqualTo(1);
        assertThat(countOutbox("case.unread_changed")).isEqualTo(1);
    }

    @Test
    void unreadProjectionFailureRollsBackCaseMessageAndDomainSignalsTogether() {
        createUser("viewer", true);
        Fixture fixture = mappedFixture("C-unread-rollback");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_unread_for_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced unread failure';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_unread_for_test_trigger
                BEFORE INSERT ON case_read_states
                FOR EACH ROW
                EXECUTE FUNCTION reject_unread_for_test()
                """);

        try {
            InboundEvent event = persist(
                    fixture.integrationId(),
                    "Ev-unread-rollback",
                    rootMessage("Ev-unread-rollback", fixture.externalChannelId(), "1721000100.000001"));

            assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.RETRY_SCHEDULED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cases", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM case_read_states", Integer.class)).isZero();
            assertThat(countOutbox("case.created")).isZero();
            assertThat(countOutbox("case.unread_changed")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_unread_for_test_trigger ON case_read_states");
            jdbc.execute("DROP FUNCTION IF EXISTS reject_unread_for_test()");
        }
    }

    private UUID createUser(String label, boolean active) {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, ?, 'USER', ?)
                RETURNING id
                """, UUID.class, label + "-" + suffix + "@example.invalid", label, active);
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

    private static String rootMessage(String eventId, String channel, String ts) {
        return "{\"type\":\"event_callback\",\"event_id\":\"" + eventId
                + "\",\"event\":{\"type\":\"message\",\"channel\":\"" + channel
                + "\",\"user\":\"U-customer\",\"text\":\"hello\",\"ts\":\"" + ts + "\"}}";
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
