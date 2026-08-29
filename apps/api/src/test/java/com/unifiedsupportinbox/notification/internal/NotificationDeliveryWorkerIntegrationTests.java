package com.unifiedsupportinbox.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.OutboxRelay;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.notification.NotificationDeliveryGateway;
import com.unifiedsupportinbox.notification.NotificationDeliveryQueue;
import com.unifiedsupportinbox.notification.NotificationDeliveryQueue.NotificationIntent;
import com.unifiedsupportinbox.notification.NotificationDeliveryStatus;
import com.unifiedsupportinbox.notification.NotificationDeliveryView;
import com.unifiedsupportinbox.notification.NotificationDestinationView;
import com.unifiedsupportinbox.notification.NotificationRuleView;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import(NotificationDeliveryWorkerIntegrationTests.FakeGatewayConfiguration.class)
class NotificationDeliveryWorkerIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final RabbitMQContainer RABBITMQ = TestInfrastructure.rabbitMq();

    private final Authentication admin = UsernamePasswordAuthenticationToken.authenticated(
            "notification-admin@example.invalid",
            "n/a",
            List.of(new SimpleGrantedAuthority("manage_notifications")));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationService configuration;
    @Autowired private NotificationDeliveryQueue queue;
    @Autowired private NotificationDeliveryService deliveries;
    @Autowired private NotificationDeliveryRepository repository;
    @Autowired private NotificationDeliveryWorker worker;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private OutboxEventStore outboxStore;
    @Autowired private FakeGateway gateway;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private TopicExchange usiOutboxExchange;
    @Autowired @Qualifier("notificationDeliveryQueue") private Queue deliveryQueue;
    @Autowired @Qualifier("notificationDeliveryDeadLetterQueue") private Queue deadLetterQueue;
    @Autowired private DirectExchange notificationDeliveryDeadLetterExchange;
    @Autowired @Qualifier("notificationDeliveryBinding") private Binding deliveryBinding;
    @Autowired @Qualifier("notificationDeliveryDeadLetterBinding") private Binding deadLetterBinding;

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
        registry.add("usi.notifications.worker.enabled", () -> false);
        registry.add("usi.notifications.worker.claim-lease", () -> "250ms");
        registry.add("usi.notifications.worker.base-retry-delay", () -> "25ms");
        registry.add("usi.notifications.worker.max-retry-delay", () -> "2s");
        registry.add("usi.notifications.worker.max-attempts", () -> 3);
    }

    @AfterAll
    static void stopInfrastructure() {
        RABBITMQ.stop();
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        TestInfrastructure.resetPostgres(POSTGRES);
        gateway.reset();
        amqpAdmin.declareExchange(usiOutboxExchange);
        amqpAdmin.declareExchange(notificationDeliveryDeadLetterExchange);
        amqpAdmin.declareQueue(deliveryQueue);
        amqpAdmin.declareQueue(deadLetterQueue);
        amqpAdmin.declareBinding(deliveryBinding);
        amqpAdmin.declareBinding(deadLetterBinding);
        amqpAdmin.purgeQueue(deliveryQueue.getName(), false);
        amqpAdmin.purgeQueue(deadLetterQueue.getName(), false);
    }

    @Test
    void enqueueIsIdempotentAndBrokerRouteOutageDoesNotLoseDelivery() {
        Route route = createRoute("SLACK", "sla_warning", true);
        NotificationDeliveryView first = enqueue("intent-broker-1", "sla_warning", "warning");
        NotificationDeliveryView duplicate = enqueue("intent-broker-1", "sla_warning", "warning");

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(count("notification_deliveries")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);

        amqpAdmin.removeBinding(deliveryBinding);
        OutboxRelay.BatchResult failedPublish = outboxRelay.publishDueBatch();
        assertThat(failedPublish.published()).isZero();
        assertThat(failedPublish.retryScheduled()).isEqualTo(1);
        assertThat(singleString("SELECT status FROM outbox_events")).isEqualTo("PENDING");

        amqpAdmin.declareBinding(deliveryBinding);
        makeOutboxDue();
        assertThat(outboxRelay.publishDueBatch().published()).isEqualTo(1);
        consumeWake(first.id());

        gateway.steps(FakeGateway.Step.success());
        assertThat(worker.process(first.id())).isEqualTo(NotificationDeliveryWorker.AttemptResult.SENT);
        assertThat(repository.findById(first.id()).orElseThrow().status()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(gateway.externalEffects()).isEqualTo(1);
        assertThat(gateway.sawDatabaseTransaction()).isFalse();
        assertThat(route.destination().id()).isEqualTo(first.destinationId());
    }

    @Test
    void retryAfterIsDurableAndRetryEventuallySends() {
        createRoute("TEAMS", "sla_breached", true);
        NotificationDeliveryView delivery = enqueue("intent-retry-1", "sla_breached", "critical");
        publishWake(delivery.id());

        gateway.steps(
                FakeGateway.Step.transientFailure("RATE_LIMITED", Duration.ofSeconds(2)),
                FakeGateway.Step.success());
        Instant before = Instant.now();
        assertThat(worker.process(delivery.id()))
                .isEqualTo(NotificationDeliveryWorker.AttemptResult.RETRY_SCHEDULED);
        DeliveryRecord retry = repository.findById(delivery.id()).orElseThrow();
        assertThat(retry.status()).isEqualTo(NotificationDeliveryStatus.RETRY_SCHEDULED);
        assertThat(retry.attempts()).isEqualTo(1);
        assertThat(retry.nextAttemptAt()).isAfterOrEqualTo(before.plusMillis(1800));

        makeDeliveryDue(delivery.id());
        assertThat(deliveries.redispatchDue()).isEqualTo(1);
        assertThat(outboxRelay.publishDueBatch().published()).isEqualTo(1);
        consumeWake(delivery.id());
        assertThat(worker.process(delivery.id())).isEqualTo(NotificationDeliveryWorker.AttemptResult.SENT);
        DeliveryRecord sent = repository.findById(delivery.id()).orElseThrow();
        assertThat(sent.status()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(sent.attempts()).isEqualTo(2);
        assertThat(gateway.calls()).isEqualTo(2);
        assertThat(gateway.sawDatabaseTransaction()).isFalse();
    }

    @Test
    void disablingConfigurationCancelsRetryAndReenableDoesNotReactivateIt() {
        Route route = createRoute("TELEGRAM", "integration_disconnected", true);
        NotificationDeliveryView delivery = enqueue(
                "intent-disabled-1", "integration_disconnected", null);
        publishWake(delivery.id());

        gateway.steps(FakeGateway.Step.transientFailure("TEMPORARY", Duration.ofMillis(50)));
        assertThat(worker.process(delivery.id()))
                .isEqualTo(NotificationDeliveryWorker.AttemptResult.RETRY_SCHEDULED);

        NotificationDestinationView disabled = configuration.setDestinationEnabled(
                admin, route.destination().id(), route.destination().version(), false);
        DeliveryRecord cancelled = repository.findById(delivery.id()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(NotificationDeliveryStatus.CANCELLED);
        assertThat(cancelled.terminalReason()).isEqualTo("SUPPRESSED_CONFIG_DISABLED");

        configuration.setDestinationEnabled(admin, disabled.id(), disabled.version(), true);
        makeDeliveryDue(delivery.id());
        assertThat(deliveries.redispatchDue()).isZero();
        assertThat(worker.process(delivery.id())).isEqualTo(NotificationDeliveryWorker.AttemptResult.NOT_CLAIMED);
        assertThat(gateway.calls()).isEqualTo(1);
        assertThat(historyReasons(delivery.id())).contains("SUPPRESSED_CONFIG_DISABLED");
    }

    @Test
    void expiredProcessingLeaseRecoversCrashBeforeProviderCall() {
        createRoute("SLACK", "sla_warning", true);
        NotificationDeliveryView delivery = enqueue("intent-lease-1", "sla_warning", "warning");
        publishWake(delivery.id());

        DeliveryRecord abandoned = deliveries.claim(delivery.id());
        assertThat(abandoned).isNotNull();
        assertThat(abandoned.status()).isEqualTo(NotificationDeliveryStatus.PROCESSING);
        expireLease(delivery.id());

        assertThat(deliveries.redispatchDue()).isEqualTo(1);
        assertThat(outboxRelay.publishDueBatch().published()).isEqualTo(1);
        consumeWake(delivery.id());
        gateway.steps(FakeGateway.Step.success());
        assertThat(worker.process(delivery.id())).isEqualTo(NotificationDeliveryWorker.AttemptResult.SENT);
        assertThat(repository.findById(delivery.id()).orElseThrow().attempts()).isEqualTo(2);
        assertThat(historyReasons(delivery.id())).contains("PROCESSING_LEASE_EXPIRED");
    }

    @Test
    void unknownProviderOutcomeRetriesWithStableIdempotencyKeyWithoutDuplicateExternalEffect() {
        createRoute("SLACK", "sla_breached", true);
        NotificationDeliveryView delivery = enqueue("intent-idempotent-1", "sla_breached", "critical");
        publishWake(delivery.id());

        gateway.steps(FakeGateway.Step.effectThenThrow(), FakeGateway.Step.success());
        assertThat(worker.process(delivery.id()))
                .isEqualTo(NotificationDeliveryWorker.AttemptResult.RETRY_SCHEDULED);
        assertThat(gateway.externalEffects()).isEqualTo(1);

        makeDeliveryDue(delivery.id());
        assertThat(deliveries.redispatchDue()).isEqualTo(1);
        assertThat(outboxRelay.publishDueBatch().published()).isEqualTo(1);
        consumeWake(delivery.id());
        assertThat(worker.process(delivery.id())).isEqualTo(NotificationDeliveryWorker.AttemptResult.SENT);

        assertThat(gateway.calls()).isEqualTo(2);
        assertThat(gateway.externalEffects()).isEqualTo(1);
        assertThat(gateway.idempotencyKeys()).containsOnly(delivery.id().toString());
    }

    @Test
    void permanentPoisonMovesToDlqAndManualReplayUsesSameDeliveryIdentity() {
        createRoute("TEAMS", "sla_warning", true);
        NotificationDeliveryView delivery = enqueue("intent-poison-1", "sla_warning", "warning");
        publishWake(delivery.id());

        gateway.steps(FakeGateway.Step.permanentFailure("MALFORMED_PAYLOAD"));
        assertThat(worker.process(delivery.id())).isEqualTo(NotificationDeliveryWorker.AttemptResult.DLQ);
        DeliveryRecord dlq = repository.findById(delivery.id()).orElseThrow();
        assertThat(dlq.status()).isEqualTo(NotificationDeliveryStatus.DLQ);
        assertThat(dlq.terminalReason()).isEqualTo("PERMANENT_FAILURE");

        NotificationDeliveryView replayed = deliveries.replayDlq(admin, delivery.id());
        assertThat(replayed.id()).isEqualTo(delivery.id());
        assertThat(replayed.replayCount()).isEqualTo(1);
        assertThat(replayed.attempts()).isZero();

        gateway.steps(FakeGateway.Step.success());
        assertThat(outboxRelay.publishDueBatch().published()).isEqualTo(1);
        consumeWake(delivery.id());
        assertThat(worker.process(delivery.id())).isEqualTo(NotificationDeliveryWorker.AttemptResult.SENT);
        assertThat(repository.findById(delivery.id()).orElseThrow().replayCount()).isEqualTo(1);
        assertThat(gateway.idempotencyKeys()).containsOnly(delivery.id().toString());
    }

    @Test
    void repeatedTransientFailuresStopAtBoundedAttemptCount() {
        createRoute("SLACK", "sla_warning", true);
        NotificationDeliveryView delivery = enqueue("intent-exhaust-1", "sla_warning", "warning");
        gateway.steps(
                FakeGateway.Step.transientFailure("TEMP", Duration.ofMillis(1)),
                FakeGateway.Step.transientFailure("TEMP", Duration.ofMillis(1)),
                FakeGateway.Step.transientFailure("TEMP", Duration.ofMillis(1)));

        for (int expectedAttempt = 1; expectedAttempt <= 3; expectedAttempt++) {
            if (expectedAttempt == 1) {
                publishWake(delivery.id());
            } else {
                makeDeliveryDue(delivery.id());
                assertThat(deliveries.redispatchDue()).isEqualTo(1);
                assertThat(outboxRelay.publishDueBatch().published()).isEqualTo(1);
                consumeWake(delivery.id());
            }
            NotificationDeliveryWorker.AttemptResult result = worker.process(delivery.id());
            if (expectedAttempt < 3) {
                assertThat(result).isEqualTo(NotificationDeliveryWorker.AttemptResult.RETRY_SCHEDULED);
            } else {
                assertThat(result).isEqualTo(NotificationDeliveryWorker.AttemptResult.DLQ);
            }
        }

        DeliveryRecord exhausted = repository.findById(delivery.id()).orElseThrow();
        assertThat(exhausted.status()).isEqualTo(NotificationDeliveryStatus.DLQ);
        assertThat(exhausted.attempts()).isEqualTo(3);
        assertThat(exhausted.terminalReason()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(gateway.calls()).isEqualTo(3);
    }

    private Route createRoute(String providerName, String eventType, boolean enabled) {
        IntegrationProvider provider = IntegrationProvider.valueOf(providerName);
        UUID integrationId = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, config_json)
                VALUES (?, ?, 'ENABLED', 'HEALTHY', '{}'::jsonb)
                RETURNING id
                """, UUID.class, provider.name(), provider.name() + " worker");
        NotificationDestinationView destination = configuration.createDestination(
                admin,
                new NotificationService.DestinationInput(
                        provider.name() + " alerts",
                        provider,
                        integrationId,
                        "target-" + provider.name().toLowerCase(),
                        enabled,
                        null,
                        null));
        NotificationRuleView rule = configuration.createRule(
                admin,
                new NotificationService.RuleInput(
                        destination.id(),
                        eventType + " rule",
                        enabled,
                        List.of(eventType),
                        List.of()));
        return new Route(destination, rule);
    }

    private NotificationDeliveryView enqueue(String intentKey, String eventType, String severity) {
        List<NotificationDeliveryView> created = queue.enqueue(new NotificationIntent(
                intentKey,
                eventType,
                severity,
                "{\"message\":\"safe test notification\"}",
                "corr-" + intentKey));
        assertThat(created).hasSize(1);
        return created.getFirst();
    }

    private void publishWake(UUID deliveryId) {
        assertThat(outboxRelay.publishDueBatch().published()).isEqualTo(1);
        consumeWake(deliveryId);
    }

    private void consumeWake(UUID deliveryId) {
        Message message = rabbit.receive(deliveryQueue.getName(), 2_000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .contains(deliveryId.toString());
    }

    private void makeOutboxDue() {
        jdbc.update("""
                UPDATE outbox_events
                SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE status = 'PENDING'
                """);
    }

    private void makeDeliveryDue(UUID id) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ? AND status = 'RETRY_SCHEDULED'
                """, id);
    }

    private void expireLease(UUID id) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ? AND status = 'PROCESSING'
                """, id);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String singleString(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private List<String> historyReasons(UUID id) {
        return jdbc.queryForList("""
                SELECT reason
                FROM notification_delivery_history
                WHERE delivery_id = ? AND reason IS NOT NULL
                ORDER BY created_at, id
                """, String.class, id);
    }

    private record Route(NotificationDestinationView destination, NotificationRuleView rule) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeGatewayConfiguration {
        @Bean
        FakeGateway fakeNotificationDeliveryGateway() {
            return new FakeGateway();
        }
    }

    static final class FakeGateway implements NotificationDeliveryGateway {
        private final ArrayDeque<Step> steps = new ArrayDeque<>();
        private final Map<String, String> externalByKey = new ConcurrentHashMap<>();
        private final java.util.Set<String> idempotencyKeys = ConcurrentHashMap.newKeySet();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger externalEffects = new AtomicInteger();
        private final AtomicBoolean transactionObserved = new AtomicBoolean();

        synchronized void steps(Step... configured) {
            steps.clear();
            steps.addAll(List.of(configured));
        }

        void reset() {
            synchronized (this) {
                steps.clear();
            }
            externalByKey.clear();
            idempotencyKeys.clear();
            calls.set(0);
            externalEffects.set(0);
            transactionObserved.set(false);
        }

        int calls() {
            return calls.get();
        }

        int externalEffects() {
            return externalEffects.get();
        }

        boolean sawDatabaseTransaction() {
            return transactionObserved.get();
        }

        java.util.Set<String> idempotencyKeys() {
            return java.util.Set.copyOf(idempotencyKeys);
        }

        @Override
        public DeliveryResult deliver(DeliveryCommand command) {
            calls.incrementAndGet();
            transactionObserved.compareAndSet(
                    false, TransactionSynchronizationManager.isActualTransactionActive());
            idempotencyKeys.add(command.idempotencyKey());
            Step step;
            synchronized (this) {
                step = steps.isEmpty() ? Step.success() : steps.removeFirst();
            }
            return switch (step.kind()) {
                case SUCCESS -> DeliveryResult.sent(externalEffect(command.idempotencyKey()));
                case TRANSIENT -> DeliveryResult.transientFailure(step.errorCode(), step.retryAfter());
                case PERMANENT -> DeliveryResult.permanentFailure(step.errorCode());
                case EFFECT_THEN_THROW -> {
                    externalEffect(command.idempotencyKey());
                    throw new IllegalStateException("simulated unknown provider outcome after side effect");
                }
            };
        }

        private String externalEffect(String key) {
            return externalByKey.computeIfAbsent(key, ignored -> {
                externalEffects.incrementAndGet();
                return "provider-message-" + key;
            });
        }

        record Step(Kind kind, String errorCode, Duration retryAfter) {
            static Step success() {
                return new Step(Kind.SUCCESS, null, null);
            }

            static Step transientFailure(String errorCode, Duration retryAfter) {
                return new Step(Kind.TRANSIENT, errorCode, retryAfter);
            }

            static Step permanentFailure(String errorCode) {
                return new Step(Kind.PERMANENT, errorCode, null);
            }

            static Step effectThenThrow() {
                return new Step(Kind.EFFECT_THEN_THROW, null, null);
            }
        }

        enum Kind {
            SUCCESS,
            TRANSIENT,
            PERMANENT,
            EFFECT_THEN_THROW
        }
    }
}
