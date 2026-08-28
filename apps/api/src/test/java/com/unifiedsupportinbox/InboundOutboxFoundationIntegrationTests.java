package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.unifiedsupportinbox.InboundEventProcessor.ProcessingResult;
import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.OutboxEventStore.OutboxEvent;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class InboundOutboxFoundationIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final RabbitMQContainer RABBITMQ = TestInfrastructure.rabbitMq();

    private final List<String> declaredQueues = new ArrayList<>();

    @Autowired
    private InboundEventStore inboundEventStore;

    @Autowired
    private InboundEventProcessor inboundEventProcessor;

    @Autowired
    private OutboxEventStore outboxEventStore;

    @Autowired
    private RabbitMqOutboxTransport transport;

    @Autowired
    private OutboxProperties outboxProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private TopicExchange usiOutboxExchange;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        RABBITMQ.start();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
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
    void resetDatabase() {
        TestInfrastructure.resetPostgres(POSTGRES);
        jdbcTemplate.execute("""
                CREATE TABLE business_effect_probe (
                    event_id uuid PRIMARY KEY,
                    value text NOT NULL
                )
                """);
        amqpAdmin.declareExchange(usiOutboxExchange);
    }

    @AfterEach
    void deleteQueues() {
        for (String queue : declaredQueues) {
            amqpAdmin.deleteQueue(queue);
        }
        declaredQueues.clear();
    }

    @Test
    void providerRetryDeduplicatesAndBusinessEffectRunsExactlyOnce() {
        UUID integrationId = UUID.randomUUID();
        InboundEvent first = inboundEventStore.persistAuthenticated(
                "slack", integrationId, "Ev-123", "{\"event\":\"message\"}", "corr-inbound-1");
        InboundEvent retry = inboundEventStore.persistAuthenticated(
                "SLACK", integrationId, "Ev-123", "{\"event\":\"message\"}", "corr-inbound-1");

        assertThat(first.id().version()).isEqualTo(7);
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(queryInt("SELECT COUNT(*) FROM inbound_events")).isEqualTo(1);

        ProcessingResult firstResult = inboundEventProcessor.process(first.id(), event ->
                jdbcTemplate.update(
                        "INSERT INTO business_effect_probe(event_id, value) VALUES (?, ?)",
                        event.id(),
                        "processed"));
        ProcessingResult retryResult = inboundEventProcessor.process(first.id(), event -> {
            throw new AssertionError("processed provider retry must not run the handler again");
        });

        assertThat(firstResult).isEqualTo(ProcessingResult.PROCESSED);
        assertThat(retryResult).isEqualTo(ProcessingResult.ALREADY_PROCESSED);
        assertThat(queryInt("SELECT COUNT(*) FROM business_effect_probe")).isEqualTo(1);

        InboundEvent stored = inboundEventStore.findById(first.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo("PROCESSED");
        assertThat(stored.attempts()).isEqualTo(1);
        assertThat(stored.processedAt()).isNotNull();
    }

    @Test
    void persistedInboundSurvivesRestartBeforeProcessing() {
        InboundEvent event = inboundEventStore.persistAuthenticated(
                "TELEGRAM",
                UUID.randomUUID(),
                "telegram-event-before-processing",
                "{\"kind\":\"message\"}",
                "corr-inbound-restart");

        assertThat(event.status()).isEqualTo("RECEIVED");
        InboundEventProcessor restartedProcessor = new InboundEventProcessor(inboundEventStore, transactionManager);
        assertThat(restartedProcessor.process(event.id(), inbound ->
                jdbcTemplate.update(
                        "INSERT INTO business_effect_probe(event_id, value) VALUES (?, ?)",
                        inbound.id(),
                        "after-restart-before-processing")))
                .isEqualTo(ProcessingResult.PROCESSED);

        assertThat(queryInt("SELECT COUNT(*) FROM business_effect_probe")).isEqualTo(1);
        assertThat(inboundEventStore.findById(event.id()).orElseThrow().status()).isEqualTo("PROCESSED");
    }

    @Test
    void processingFailureRollsBackBusinessWorkAndCanBeRetriedAfterRestart() {
        InboundEvent event = inboundEventStore.persistAuthenticated(
                "TEAMS",
                UUID.randomUUID(),
                "teams-event-1",
                "{\"kind\":\"message\"}",
                "corr-inbound-2");

        assertThatThrownBy(() -> inboundEventProcessor.process(event.id(), "SIMULATED_CRASH", inbound -> {
            jdbcTemplate.update(
                    "INSERT INTO business_effect_probe(event_id, value) VALUES (?, ?)",
                    inbound.id(),
                    "must-roll-back");
            throw new SimulatedCrashException();
        })).isInstanceOf(SimulatedCrashException.class);

        assertThat(queryInt("SELECT COUNT(*) FROM business_effect_probe")).isZero();
        InboundEvent failed = inboundEventStore.findById(event.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.errorCode()).isEqualTo("SIMULATED_CRASH");

        InboundEventProcessor restartedProcessor = new InboundEventProcessor(inboundEventStore, transactionManager);
        assertThat(restartedProcessor.process(event.id(), inbound ->
                jdbcTemplate.update(
                        "INSERT INTO business_effect_probe(event_id, value) VALUES (?, ?)",
                        inbound.id(),
                        "after-restart")))
                .isEqualTo(ProcessingResult.PROCESSED);

        assertThat(queryInt("SELECT COUNT(*) FROM business_effect_probe")).isEqualTo(1);
        InboundEvent recovered = inboundEventStore.findById(event.id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo("PROCESSED");
        assertThat(recovered.attempts()).isEqualTo(2);
    }

    @Test
    void domainWriteAndOutboxAppendRollBackTogether() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        UUID aggregateId = UUID.randomUUID();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "INSERT INTO business_effect_probe(event_id, value) VALUES (?, ?)",
                    aggregateId,
                    "domain-write");
            outboxEventStore.append(
                    "case.created",
                    "case",
                    aggregateId,
                    "{\"caseId\":\"" + aggregateId + "\"}",
                    "corr-outbox-rollback");
            throw new SimulatedCrashException();
        })).isInstanceOf(SimulatedCrashException.class);

        assertThat(queryInt("SELECT COUNT(*) FROM business_effect_probe")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM outbox_events")).isZero();
    }

    @Test
    void committedOutboxPublishesAfterRelayRestartAndDoesNotRepublishAfterCheckpoint() {
        String queue = declareQueue("case.created");
        UUID aggregateId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        OutboxEvent event = transaction.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO business_effect_probe(event_id, value) VALUES (?, ?)",
                    aggregateId,
                    "committed-before-publish");
            return outboxEventStore.append(
                    "case.created",
                    "case",
                    aggregateId,
                    "{\"caseId\":\"" + aggregateId + "\"}",
                    "corr-outbox-1");
        });
        assertThat(event).isNotNull();
        assertThat(event.id().version()).isEqualTo(7);
        assertThat(queryString("SELECT status FROM outbox_events WHERE id = ?", event.id()))
                .isEqualTo("PENDING");

        OutboxRelay restartedRelay = new OutboxRelay(outboxEventStore, transport, outboxProperties);
        OutboxRelay.BatchResult firstBatch = restartedRelay.publishDueBatch();

        assertThat(firstBatch.claimed()).isEqualTo(1);
        assertThat(firstBatch.published()).isEqualTo(1);
        assertThat(firstBatch.retryScheduled()).isZero();
        Message published = rabbitTemplate.receive(queue, 2_000);
        assertPublishedMessage(published, event);

        OutboxEvent stored = outboxEventStore.findById(event.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo("PUBLISHED");
        assertThat(stored.attempts()).isEqualTo(1);
        assertThat(stored.publishedAt()).isNotNull();

        assertThat(restartedRelay.publishDueBatch().claimed()).isZero();
        assertThat(rabbitTemplate.receive(queue, 100)).isNull();
    }

    @Test
    void expiredProcessingLeaseRecoversCrashBeforeBrokerPublish() {
        String queue = declareQueue("case.recover");
        OutboxEvent event = appendOutbox("case.recover", "corr-outbox-2");

        List<OutboxEvent> claimed = outboxEventStore.claimDue(1, Duration.ofMinutes(5));
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().status()).isEqualTo("PROCESSING");
        assertThat(claimed.getFirst().attempts()).isEqualTo(1);

        expireClaim(event.id());
        OutboxRelay restartedRelay = new OutboxRelay(outboxEventStore, transport, outboxProperties);
        assertThat(restartedRelay.publishDueBatch().published()).isEqualTo(1);

        Message published = rabbitTemplate.receive(queue, 2_000);
        assertPublishedMessage(published, event);
        OutboxEvent recovered = outboxEventStore.findById(event.id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo("PUBLISHED");
        assertThat(recovered.attempts()).isEqualTo(2);
    }

    @Test
    void brokerAckBeforeDatabaseCheckpointMayRedeliverButStableEventIdPreventsDuplicateEffect() {
        String queue = declareQueue("case.at-least-once");
        OutboxEvent event = appendOutbox("case.at-least-once", "corr-outbox-3");

        OutboxEvent claimed = outboxEventStore.claimDue(1, Duration.ofMinutes(5)).getFirst();
        transport.publish(claimed);
        // Simulated hard crash here: broker accepted the message, but PUBLISHED was never persisted.
        expireClaim(event.id());

        OutboxRelay restartedRelay = new OutboxRelay(outboxEventStore, transport, outboxProperties);
        assertThat(restartedRelay.publishDueBatch().published()).isEqualTo(1);

        Message firstDelivery = rabbitTemplate.receive(queue, 2_000);
        Message secondDelivery = rabbitTemplate.receive(queue, 2_000);
        assertThat(firstDelivery).isNotNull();
        assertThat(secondDelivery).isNotNull();
        assertThat(firstDelivery.getMessageProperties().getMessageId()).isEqualTo(event.id().toString());
        assertThat(secondDelivery.getMessageProperties().getMessageId()).isEqualTo(event.id().toString());

        consumeIdempotently(firstDelivery);
        consumeIdempotently(secondDelivery);
        assertThat(queryInt("SELECT COUNT(*) FROM business_effect_probe")).isEqualTo(1);

        OutboxEvent stored = outboxEventStore.findById(event.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo("PUBLISHED");
        assertThat(stored.attempts()).isEqualTo(2);
    }

    @Test
    void unroutableBrokerReturnStaysPendingUntilAConsumerRouteExists() {
        String eventType = "case.route." + UUID.randomUUID();
        OutboxEvent event = appendOutbox(eventType, "corr-outbox-4");
        OutboxRelay relay = new OutboxRelay(outboxEventStore, transport, outboxProperties);

        OutboxRelay.BatchResult returnedBatch = relay.publishDueBatch();
        assertThat(returnedBatch.claimed()).isEqualTo(1);
        assertThat(returnedBatch.published()).isZero();
        assertThat(returnedBatch.retryScheduled()).isEqualTo(1);
        OutboxEvent pending = outboxEventStore.findById(event.id()).orElseThrow();
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.publishedAt()).isNull();

        String queue = declareQueue(eventType);
        expireClaim(event.id());
        assertThat(relay.publishDueBatch().published()).isEqualTo(1);
        assertPublishedMessage(rabbitTemplate.receive(queue, 2_000), event);
    }

    private OutboxEvent appendOutbox(String type, String correlationId) {
        UUID aggregateId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        OutboxEvent event = transaction.execute(status -> outboxEventStore.append(
                type,
                "case",
                aggregateId,
                "{\"caseId\":\"" + aggregateId + "\"}",
                correlationId));
        return java.util.Objects.requireNonNull(event);
    }

    private String declareQueue(String routingKey) {
        Queue queue = new Queue("usi60-" + UUID.randomUUID(), true, false, false);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(usiOutboxExchange).with(routingKey));
        declaredQueues.add(queue.getName());
        return queue.getName();
    }

    private void expireClaim(UUID eventId) {
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, eventId);
    }

    private void consumeIdempotently(Message message) {
        UUID eventId = UUID.fromString(message.getMessageProperties().getMessageId());
        jdbcTemplate.update("""
                INSERT INTO business_effect_probe(event_id, value)
                VALUES (?, 'consumed')
                ON CONFLICT (event_id) DO NOTHING
                """, eventId);
    }

    private void assertPublishedMessage(Message message, OutboxEvent event) {
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.id().toString());
        assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo(event.correlationId());
        String eventIdHeader = message.getMessageProperties().getHeader(RabbitMqOutboxTransport.EVENT_ID_HEADER);
        String eventTypeHeader = message.getMessageProperties().getHeader(RabbitMqOutboxTransport.EVENT_TYPE_HEADER);
        assertThat(eventIdHeader).isEqualTo(event.id().toString());
        assertThat(eventTypeHeader).isEqualTo(event.type());
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(event.payloadJson());
    }

    private int queryInt(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return java.util.Objects.requireNonNull(value);
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private static final class SimulatedCrashException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
