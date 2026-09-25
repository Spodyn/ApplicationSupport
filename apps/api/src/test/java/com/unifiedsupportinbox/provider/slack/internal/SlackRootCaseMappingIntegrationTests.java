package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.testing.TestInfrastructure;
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
class SlackRootCaseMappingIntegrationTests {

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
    void rootMessageCreatesOneNewCaseFirstMessageAndDurableCaseCreatedEvent() {
        Fixture fixture = mappedFixture("C-support");
        String ts = "1720000000.123456";
        InboundEvent first = persist(
                fixture.integrationId(),
                "Ev-root-first",
                rootMessage("Ev-root-first", fixture.externalChannelId(), "U-customer", "hello from Slack", ts));

        assertThat(worker.process(first.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);

        assertThat(count("cases")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(1);
        assertThat(countOutbox("case.created")).isEqualTo(1);

        Map<String, Object> caseRow = jdbc.queryForMap("""
                SELECT reference, customer_id, integration_id, channel_id, provider,
                       external_conversation_id, external_thread_key, status, owner_user_id
                FROM cases
                """);
        assertThat((String) caseRow.get("reference")).matches("CASE-[0-9]{8,}");
        assertThat(caseRow.get("customer_id")).isEqualTo(fixture.customerId());
        assertThat(caseRow.get("integration_id")).isEqualTo(fixture.integrationId());
        assertThat(caseRow.get("channel_id")).isEqualTo(fixture.channelId());
        assertThat(caseRow.get("provider")).isEqualTo("SLACK");
        assertThat(caseRow.get("external_conversation_id")).isEqualTo(fixture.externalChannelId());
        assertThat(caseRow.get("external_thread_key")).isEqualTo(ts);
        assertThat(caseRow.get("status")).isEqualTo("NEW");
        assertThat(caseRow.get("owner_user_id")).isNull();

        Map<String, Object> messageRow = jdbc.queryForMap("""
                SELECT case_id, external_message_id, external_thread_key, kind,
                       author_external_id, body, body_format, inbound, delivery_status,
                       provider_created_at, correlation_id
                FROM messages
                """);
        assertThat(messageRow.get("case_id")).isEqualTo(jdbc.queryForObject("SELECT id FROM cases", UUID.class));
        assertThat(messageRow.get("external_message_id")).isEqualTo(ts);
        assertThat(messageRow.get("external_thread_key")).isEqualTo(ts);
        assertThat(messageRow.get("kind")).isEqualTo("CUSTOMER");
        assertThat(messageRow.get("author_external_id")).isEqualTo("U-customer");
        assertThat(messageRow.get("body")).isEqualTo("hello from Slack");
        assertThat(messageRow.get("body_format")).isEqualTo("PLAIN_TEXT");
        assertThat(messageRow.get("inbound")).isEqualTo(true);
        assertThat(messageRow.get("delivery_status")).isNull();
        assertThat(messageRow.get("provider_created_at")).isNotNull();
        assertThat(messageRow.get("correlation_id")).isEqualTo("corr-Ev-root-first");

        assertThat(worker.process(first.id())).isEqualTo(SlackInboundWorker.AttemptResult.ALREADY_PROCESSED);
        assertThat(count("cases")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(1);
        assertThat(countOutbox("case.created")).isEqualTo(1);
    }

    @Test
    void secondSlackEventForSameProviderMessageIsIdempotent() {
        Fixture fixture = mappedFixture("C-support-dedup");
        String ts = "1720000100.000001";

        InboundEvent first = persist(
                fixture.integrationId(),
                "Ev-root-a",
                rootMessage("Ev-root-a", fixture.externalChannelId(), "U-customer", "same logical message", ts));
        InboundEvent duplicate = persist(
                fixture.integrationId(),
                "Ev-root-b",
                rootMessage("Ev-root-b", fixture.externalChannelId(), "U-customer", "same logical message", ts));

        assertThat(worker.process(first.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(worker.process(duplicate.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);

        assertThat(count("cases")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(1);
        assertThat(countOutbox("case.created")).isEqualTo(1);
    }

    @Test
    void customerUnmappedChannelRetriesWithoutCreatingBusinessRows() {
        UUID integrationId = createSlackIntegration();
        UUID channelId = createChannel(integrationId, "C-unmapped-customer", null, true, false);
        assertThat(channelId).isNotNull();
        InboundEvent event = persist(
                integrationId,
                "Ev-no-customer",
                rootMessage("Ev-no-customer", "C-unmapped-customer", "U-customer", "map me first", "1720000200.1"));

        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.RETRY_SCHEDULED);
        assertThat(count("cases")).isZero();
        assertThat(count("messages")).isZero();
        assertThat(countOutbox("case.created")).isZero();
    }

    @Test
    void messagePersistenceFailureRollsBackCaseAndCaseCreatedOutbox() {
        Fixture fixture = mappedFixture("C-rollback");
        jdbc.execute("ALTER TABLE messages ADD CONSTRAINT ck_test_reject_body CHECK (body <> 'force-rollback')");
        InboundEvent event = persist(
                fixture.integrationId(),
                "Ev-rollback",
                rootMessage("Ev-rollback", fixture.externalChannelId(), "U-customer", "force-rollback", "1720000300.1"));

        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.RETRY_SCHEDULED);
        assertThat(count("cases")).isZero();
        assertThat(count("messages")).isZero();
        assertThat(countOutbox("case.created")).isZero();
    }

    @Test
    void threadReplyRoutesToExistingCaseAndCreatesOnlyOneAdditionalMessage() {
        Fixture fixture = mappedFixture("C-thread-existing");
        String rootTs = "1720000400.1";
        String replyTs = "1720000400.2";

        InboundEvent root = persist(
                fixture.integrationId(),
                "Ev-thread-root",
                rootMessage("Ev-thread-root", fixture.externalChannelId(), "U-customer", "root", rootTs));
        InboundEvent reply = persist(
                fixture.integrationId(),
                "Ev-thread-reply",
                threadReply(
                        "Ev-thread-reply",
                        fixture.externalChannelId(),
                        "U-customer",
                        "reply",
                        replyTs,
                        rootTs));

        assertThat(worker.process(root.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        UUID caseId = jdbc.queryForObject("SELECT id FROM cases", UUID.class);

        assertThat(worker.process(reply.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);

        assertThat(count("cases")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(2);
        assertThat(countOutbox("case.created")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT case_id FROM messages WHERE external_message_id = ?", UUID.class, replyTs))
                .isEqualTo(caseId);
        assertThat(jdbc.queryForObject(
                "SELECT external_thread_key FROM messages WHERE external_message_id = ?", String.class, replyTs))
                .isEqualTo(rootTs);
    }

    @Test
    void duplicateThreadReplyDoesNotCreateDuplicateMessageOrCase() {
        Fixture fixture = mappedFixture("C-thread-dedup");
        String rootTs = "1720000500.1";
        String replyTs = "1720000500.2";

        InboundEvent root = persist(
                fixture.integrationId(),
                "Ev-thread-root-dedup",
                rootMessage("Ev-thread-root-dedup", fixture.externalChannelId(), "U-customer", "root", rootTs));
        InboundEvent reply = persist(
                fixture.integrationId(),
                "Ev-thread-reply-a",
                threadReply(
                        "Ev-thread-reply-a",
                        fixture.externalChannelId(),
                        "U-customer",
                        "reply",
                        replyTs,
                        rootTs));
        InboundEvent duplicate = persist(
                fixture.integrationId(),
                "Ev-thread-reply-b",
                threadReply(
                        "Ev-thread-reply-b",
                        fixture.externalChannelId(),
                        "U-customer",
                        "reply",
                        replyTs,
                        rootTs));

        assertThat(worker.process(root.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(worker.process(reply.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(worker.process(duplicate.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);

        assertThat(count("cases")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(2);
        assertThat(countOutbox("case.created")).isEqualTo(1);
    }

    @Test
    void outsideBusinessHoursQueuesOneDurableSystemOooResponsePerCaseAndClosure() {
        jdbc.update("DELETE FROM business_hour_intervals");
        jdbc.update("""
                INSERT INTO business_hour_intervals (business_hours_id, day_of_week, start_time, end_time)
                SELECT id, EXTRACT(ISODOW FROM CURRENT_TIMESTAMP + INTERVAL '1 day')::smallint, TIME '09:00', TIME '17:00'
                FROM business_hours WHERE active = TRUE
                """);
        jdbc.update("UPDATE ooo_config SET enabled = TRUE, message_template = 'We are closed until {{next_opening_time}}.'");
        Fixture fixture = mappedFixture("C-ooo");
        InboundEvent root = persist(fixture.integrationId(), "Ev-ooo-root",
                rootMessage("Ev-ooo-root", fixture.externalChannelId(), "U-customer", "root", "1720000600.1"));
        InboundEvent reply = persist(fixture.integrationId(), "Ev-ooo-reply",
                threadReply("Ev-ooo-reply", fixture.externalChannelId(), "U-customer", "again", "1720000600.2", "1720000600.1"));

        assertThat(worker.process(root.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(worker.process(reply.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);

        assertThat(count("ooo_deliveries")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT kind FROM messages WHERE delivery_status = 'QUEUED'", String.class))
                .isEqualTo("SYSTEM");
        assertThat(countOutbox("message.send_requested")).isEqualTo(1);
    }

    private Fixture mappedFixture(String externalChannelId) {
        UUID customerId = createCustomer();
        UUID integrationId = createSlackIntegration();
        UUID channelId = createChannel(integrationId, externalChannelId, customerId, true, false);
        return new Fixture(customerId, integrationId, channelId, externalChannelId);
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

    private UUID createChannel(
            UUID integrationId,
            String externalChannelId,
            UUID customerId,
            boolean active,
            boolean ignored) {
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, customer_id, ignored,
                    grouping_strategy, active
                ) VALUES (?, ?, ?, 'support', ?, ?, 'SLACK_ROOT_THREAD', ?)
                """, channelId, integrationId, externalChannelId, customerId, ignored, active);
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

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countOutbox(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE type = ?", Integer.class, type);
    }

    private record Fixture(UUID customerId, UUID integrationId, UUID channelId, String externalChannelId) {
    }
}
