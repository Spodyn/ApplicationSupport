package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.OutboxProperties;
import com.unifiedsupportinbox.OutboxRelay;
import com.unifiedsupportinbox.RabbitMqOutboxTransport;
import com.unifiedsupportinbox.provider.slack.internal.SlackInboundEventHandler.SlackInboundEvent;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "usi.slack.inbound-worker.enabled=false",
                "usi.slack.inbound-worker.max-attempts=2",
                "usi.slack.inbound-worker.base-retry-delay=50ms",
                "usi.slack.inbound-worker.max-retry-delay=100ms"
        })
@ActiveProfiles("test")
@Import(SlackInboundWorkerIntegrationTests.HandlerConfiguration.class)
class SlackInboundWorkerIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final RabbitMQContainer RABBITMQ = TestInfrastructure.rabbitMq();

    @Autowired
    private SlackInboundDeliveryService deliveries;

    @Autowired
    private SlackInboundWorker worker;

    @Autowired
    private OutboxEventStore outboxStore;

    @Autowired
    private RabbitMqOutboxTransport outboxTransport;

    @Autowired
    private OutboxProperties outboxProperties;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private TopicExchange usiOutboxExchange;

    @Autowired
    @Qualifier("slackInboundQueue")
    private Queue slackInboundQueue;

    @Autowired
    private TestSlackInboundHandler handler;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        RABBITMQ.start();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        registry.add("usi.outbox.relay-enabled", () -> false);
    }

    @AfterAll
    static void stopInfrastructure() {
        RABBITMQ.stop();
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        TestInfrastructure.resetPostgres(POSTGRES);
        jdbc.execute("""
                CREATE TABLE slack_business_effect_probe (
                    inbound_event_id uuid PRIMARY KEY,
                    value text NOT NULL
                )
                """);
        handler.reset();
        declareInboundRoute();
        amqpAdmin.purgeQueue(SlackInboundRabbitConfiguration.INBOUND_QUEUE);
    }

    @Test
    void duplicateSlackDeliveriesShareOneDurableRowOneWakeupAndOneBusinessEffect() {
        UUID integrationId = UUID.randomUUID();
        InboundEvent first = persist(integrationId, "Ev-duplicate", validCallback("Ev-duplicate"));
        InboundEvent duplicate = persist(integrationId, "Ev-duplicate", validCallback("Ev-duplicate"));

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(queryInt("SELECT count(*) FROM inbound_events")).isEqualTo(1);
        assertThat(queryInt("SELECT count(*) FROM outbox_events WHERE type = 'slack.inbound.received'"))
                .isEqualTo(1);
        assertThat(current(first.id()).wakePending()).isTrue();

        publishWakeAndConsume(first.id());
        assertThat(worker.process(first.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(worker.process(first.id())).isEqualTo(SlackInboundWorker.AttemptResult.ALREADY_PROCESSED);

        InboundEvent processed = current(first.id());
        assertThat(processed.status()).isEqualTo("PROCESSED");
        assertThat(processed.attempts()).isEqualTo(1);
        assertThat(processed.wakePending()).isFalse();
        assertThat(queryInt("SELECT count(*) FROM slack_business_effect_probe")).isEqualTo(1);
        assertThat(handler.calls()).isEqualTo(1);
    }

    @Test
    void brokerRouteOutageKeepsDurableWakeupUntilRouteReturns() {
        amqpAdmin.deleteQueue(SlackInboundRabbitConfiguration.INBOUND_QUEUE);
        InboundEvent event = persist(UUID.randomUUID(), "Ev-broker-outage", validCallback("Ev-broker-outage"));

        OutboxRelay relay = relay();
        OutboxRelay.BatchResult unavailable = relay.publishDueBatch();
        assertThat(unavailable.claimed()).isEqualTo(1);
        assertThat(unavailable.published()).isZero();
        assertThat(unavailable.retryScheduled()).isEqualTo(1);
        assertThat(current(event.id()).status()).isEqualTo("RECEIVED");
        assertThat(current(event.id()).wakePending()).isTrue();
        assertThat(deliveries.redispatchDue()).isZero();

        declareInboundRoute();
        jdbc.update("""
                UPDATE outbox_events
                SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE aggregate_id = ? AND status = 'PENDING'
                """, event.id());

        assertThat(relay.publishDueBatch().published()).isEqualTo(1);
        assertWake(rabbitTemplate.receive(SlackInboundRabbitConfiguration.INBOUND_QUEUE, 2_000), event.id());
        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(current(event.id()).status()).isEqualTo("PROCESSED");
    }

    @Test
    void crashDuringProcessingRollsBackBusinessEffectAndRetriesAfterRestartStyleRedispatch() {
        InboundEvent event = persist(UUID.randomUUID(), "Ev-crash", validCallback("Ev-crash"));
        publishWakeAndConsume(event.id());
        handler.mode = Mode.CRASH;

        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.RETRY_SCHEDULED);
        InboundEvent failed = current(event.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.failureCategory()).isEqualTo("TRANSIENT");
        assertThat(failed.errorCode()).isEqualTo("UNEXPECTED_PROCESSING_FAILURE");
        assertThat(queryInt("SELECT count(*) FROM slack_business_effect_probe")).isZero();

        forceInboundDue(event.id());
        assertThat(deliveries.redispatchDue()).isEqualTo(1);
        publishWakeAndConsume(event.id());

        handler.mode = Mode.SUCCESS;
        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        InboundEvent recovered = current(event.id());
        assertThat(recovered.status()).isEqualTo("PROCESSED");
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(queryInt("SELECT count(*) FROM slack_business_effect_probe")).isEqualTo(1);
    }

    @Test
    void malformedPoisonPayloadMovesToControlledDatabaseDlqWithoutRedispatch() {
        InboundEvent event = persist(
                UUID.randomUUID(),
                "Ev-poison",
                "{\"type\":\"event_callback\",\"event_id\":\"Ev-poison\",\"event\":\"not-an-object\"}");
        publishWakeAndConsume(event.id());

        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.DLQ);
        InboundEvent dlq = current(event.id());
        assertThat(dlq.status()).isEqualTo("DLQ");
        assertThat(dlq.failureCategory()).isEqualTo("MALFORMED");
        assertThat(dlq.errorCode()).isEqualTo("MALFORMED_EVENT");
        assertThat(dlq.deadLetteredAt()).isNotNull();
        assertThat(dlq.wakePending()).isFalse();
        assertThat(deliveries.redispatchDue()).isZero();
        assertThat(queryInt("SELECT count(*) FROM slack_business_effect_probe")).isZero();
    }

    @Test
    void transientFailuresAreBoundedAndMoveToDlqAfterConfiguredAttempts() {
        InboundEvent event = persist(UUID.randomUUID(), "Ev-exhausted", validCallback("Ev-exhausted"));
        handler.mode = Mode.TRANSIENT;

        publishWakeAndConsume(event.id());
        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.RETRY_SCHEDULED);
        forceInboundDue(event.id());
        assertThat(deliveries.redispatchDue()).isEqualTo(1);
        publishWakeAndConsume(event.id());
        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.DLQ);

        InboundEvent dlq = current(event.id());
        assertThat(dlq.status()).isEqualTo("DLQ");
        assertThat(dlq.attempts()).isEqualTo(2);
        assertThat(dlq.failureCategory()).isEqualTo("EXHAUSTED");
        assertThat(dlq.errorCode()).isEqualTo("TEMPORARY_TEST_FAILURE");
        assertThat(queryInt("SELECT count(*) FROM slack_business_effect_probe")).isZero();
    }

    @Test
    void permanentHandlerFailureMovesDirectlyToDlq() {
        InboundEvent event = persist(UUID.randomUUID(), "Ev-permanent", validCallback("Ev-permanent"));
        handler.mode = Mode.PERMANENT;
        publishWakeAndConsume(event.id());

        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.DLQ);
        InboundEvent dlq = current(event.id());
        assertThat(dlq.status()).isEqualTo("DLQ");
        assertThat(dlq.failureCategory()).isEqualTo("PERMANENT");
        assertThat(dlq.errorCode()).isEqualTo("PERMANENT_TEST_FAILURE");
        assertThat(dlq.attempts()).isEqualTo(1);
    }

    private InboundEvent persist(UUID integrationId, String eventId, String payload) {
        return deliveries.persistAndWake(integrationId, eventId, payload, "corr-" + eventId);
    }

    private void publishWakeAndConsume(UUID eventId) {
        OutboxRelay.BatchResult result = relay().publishDueBatch();
        assertThat(result.published()).isEqualTo(1);
        assertWake(rabbitTemplate.receive(SlackInboundRabbitConfiguration.INBOUND_QUEUE, 2_000), eventId);
    }

    private OutboxRelay relay() {
        return new OutboxRelay(outboxStore, outboxTransport, outboxProperties);
    }

    private void declareInboundRoute() {
        amqpAdmin.declareExchange(usiOutboxExchange);
        amqpAdmin.declareQueue(slackInboundQueue);
        amqpAdmin.declareBinding(BindingBuilder.bind(slackInboundQueue)
                .to(usiOutboxExchange)
                .with(SlackInboundDeliveryService.OUTBOX_TYPE));
    }

    private void assertWake(Message message, UUID eventId) {
        assertThat(message).isNotNull();
        String body = new String(message.getBody(), StandardCharsets.UTF_8).replace(" ", "");
        assertThat(body).contains("\"inboundEventId\":\"" + eventId + "\"");
    }

    private InboundEvent current(UUID id) {
        return jdbc.query("""
                SELECT id, provider, integration_id, external_event_id, payload_json::text AS payload_json,
                       status, received_at, processed_at, error_code, attempts, correlation_id,
                       failure_category, next_attempt_at, wake_pending, dead_lettered_at
                FROM inbound_events WHERE id = ?
                """, (rs, row) -> new InboundEvent(
                rs.getObject("id", UUID.class),
                rs.getString("provider"),
                rs.getObject("integration_id", UUID.class),
                rs.getString("external_event_id"),
                rs.getString("payload_json"),
                rs.getString("status"),
                rs.getObject("received_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("processed_at", java.time.OffsetDateTime.class) == null
                        ? null : rs.getObject("processed_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getString("error_code"),
                rs.getInt("attempts"),
                rs.getString("correlation_id"),
                rs.getString("failure_category"),
                rs.getObject("next_attempt_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getBoolean("wake_pending"),
                rs.getObject("dead_lettered_at", java.time.OffsetDateTime.class) == null
                        ? null : rs.getObject("dead_lettered_at", java.time.OffsetDateTime.class).toInstant()), id).getFirst();
    }

    private void forceInboundDue(UUID id) {
        jdbc.update("""
                UPDATE inbound_events
                SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, id);
    }

    private int queryInt(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static String validCallback(String eventId) {
        return "{\"type\":\"event_callback\",\"event_id\":\"" + eventId
                + "\",\"event\":{\"type\":\"message\",\"text\":\"hello\"}}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HandlerConfiguration {
        @Bean
        TestSlackInboundHandler testSlackInboundHandler(JdbcTemplate jdbc) {
            return new TestSlackInboundHandler(jdbc);
        }
    }

    enum Mode {
        SUCCESS,
        TRANSIENT,
        PERMANENT,
        CRASH
    }

    static final class TestSlackInboundHandler implements SlackInboundEventHandler {
        private final JdbcTemplate jdbc;
        private int calls;
        private Mode mode = Mode.SUCCESS;

        TestSlackInboundHandler(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void handle(SlackInboundEvent event) {
            calls++;
            jdbc.update("""
                    INSERT INTO slack_business_effect_probe(inbound_event_id, value)
                    VALUES (?, 'handled')
                    ON CONFLICT (inbound_event_id) DO NOTHING
                    """, event.inboundEventId());
            switch (mode) {
                case SUCCESS -> { }
                case TRANSIENT -> throw SlackInboundProcessingException.transientFailure(
                        "TEMPORARY_TEST_FAILURE", "temporary test failure");
                case PERMANENT -> throw SlackInboundProcessingException.permanentFailure(
                        "PERMANENT_TEST_FAILURE", "permanent test failure");
                case CRASH -> throw new SimulatedWorkerCrash();
            }
        }

        int calls() {
            return calls;
        }

        void reset() {
            calls = 0;
            mode = Mode.SUCCESS;
        }
    }

    static final class SimulatedWorkerCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
