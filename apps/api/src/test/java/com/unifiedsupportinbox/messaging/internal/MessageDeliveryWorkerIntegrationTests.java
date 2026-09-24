package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryCommand;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryResult;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import(MessageDeliveryWorkerIntegrationTests.FakeProviderConfiguration.class)
class MessageDeliveryWorkerIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SupportSendMessageService send;
    @Autowired private MessageDeliveryService deliveries;
    @Autowired private MessageDeliveryWorker worker;
    @Autowired private FakeProvider provider;

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
        registry.add("usi.messages.delivery-worker.enabled", () -> false);
        registry.add("usi.messages.delivery-worker.claim-lease", () -> "250ms");
        registry.add("usi.messages.delivery-worker.base-retry-delay", () -> "25ms");
        registry.add("usi.messages.delivery-worker.max-retry-delay", () -> "2s");
        registry.add("usi.messages.delivery-worker.retry-window", () -> "24h");
        registry.add("usi.messages.delivery-worker.max-automatic-attempts", () -> 8);
        registry.add("usi.bootstrap-admin.enabled", () -> false);
    }

    @AfterAll
    static void stopInfrastructure() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        TestInfrastructure.resetPostgres(POSTGRES);
        provider.reset();
    }

    @Test
    void transientRetryAfterIsDurableAndSameMessageEventuallySends() {
        Fixture fixture = createFixture("ENABLED");
        UUID messageId = createMessage(fixture, "retry-after");
        provider.steps(
                DeliveryResult.transientFailure("RATE_LIMITED", Duration.ofSeconds(2)),
                DeliveryResult.sent("slack-ts-1"));

        Instant before = Instant.now();
        assertThat(worker.process(messageId)).isEqualTo(MessageDeliveryWorker.AttemptResult.RETRY_SCHEDULED);
        assertThat(status(messageId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT error_category FROM delivery_attempts WHERE message_id = ? AND attempt_no = 1",
                String.class, messageId)).isEqualTo("TRANSIENT");
        Instant nextRetry = jdbc.queryForObject(
                "SELECT next_retry_at FROM delivery_attempts WHERE message_id = ? AND attempt_no = 1",
                java.time.OffsetDateTime.class,
                messageId).toInstant();
        assertThat(nextRetry).isAfterOrEqualTo(before.plusMillis(1800));

        makeRetryDue(messageId);
        assertThat(deliveries.redispatchDue()).isEqualTo(1);
        assertThat(worker.process(messageId)).isEqualTo(MessageDeliveryWorker.AttemptResult.SENT);
        assertThat(status(messageId)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM delivery_attempts WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(2);
        assertThat(provider.calls()).isEqualTo(2);
        assertThat(provider.idempotencyKey()).isEqualTo(messageId.toString());
        assertThat(provider.sawDatabaseTransaction()).isFalse();
    }

    @Test
    void permanentFailureStopsAutomaticDelivery() {
        Fixture fixture = createFixture("ENABLED");
        UUID messageId = createMessage(fixture, "permanent");
        provider.steps(DeliveryResult.permanentFailure("CHANNEL_NOT_FOUND"));

        assertThat(worker.process(messageId)).isEqualTo(MessageDeliveryWorker.AttemptResult.FAILED);
        assertThat(status(messageId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT error_category FROM delivery_attempts WHERE message_id = ? AND attempt_no = 1",
                String.class, messageId)).isEqualTo("PERMANENT");
        assertThat(jdbc.queryForObject(
                "SELECT next_retry_at IS NULL FROM delivery_attempts WHERE message_id = ? AND attempt_no = 1",
                Boolean.class, messageId)).isTrue();
    }

    @Test
    void expiredSendingLeaseRecoversAfterWorkerCrash() {
        Fixture fixture = createFixture("ENABLED");
        UUID messageId = createMessage(fixture, "crash");

        MessageDeliveryService.DeliveryClaim abandoned = deliveries.claim(messageId);
        assertThat(abandoned).isNotNull();
        assertThat(status(messageId)).isEqualTo("SENDING");
        jdbc.update("""
                UPDATE delivery_attempts
                SET next_retry_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE message_id = ? AND attempt_no = 1
                """, messageId);

        assertThat(deliveries.redispatchDue()).isEqualTo(1);
        assertThat(status(messageId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM delivery_attempts WHERE message_id = ? AND attempt_no = 1",
                String.class, messageId)).isEqualTo("WORKER_LEASE_EXPIRED");

        provider.steps(DeliveryResult.delivered("slack-ts-recovered"));
        assertThat(worker.process(messageId)).isEqualTo(MessageDeliveryWorker.AttemptResult.DELIVERED);
        assertThat(status(messageId)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM delivery_attempts WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(2);
    }

    @Test
    void duplicateManualRetryReusesSameLogicalMessageAndOutboxIntent() {
        Fixture fixture = createFixture("DISABLED");
        UUID messageId = createMessage(fixture, "manual-retry");

        assertThat(worker.process(messageId)).isEqualTo(MessageDeliveryWorker.AttemptResult.FAILED);
        assertThat(status(messageId)).isEqualTo("FAILED");
        jdbc.update("UPDATE integrations SET status = 'ENABLED' WHERE id = ?", fixture.integrationId());
        int beforeOutbox = countOutbox(messageId);

        IdempotencyResult first = deliveries.retry(
                messageId, fixture.ownerId(), "retry-key-1", "corr-retry-1");
        IdempotencyResult duplicate = deliveries.retry(
                messageId, fixture.ownerId(), "retry-key-1", "corr-retry-2");

        assertThat(first.replayed()).isFalse();
        assertThat(duplicate.replayed()).isTrue();
        assertThat(duplicate.body()).isEqualTo(first.body());
        assertThat(UUID.fromString(first.body().get("messageId").asText())).isEqualTo(messageId);
        assertThat(status(messageId)).isEqualTo("QUEUED");
        assertThat(countOutbox(messageId)).isEqualTo(beforeOutbox + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE id = ?", Integer.class, messageId))
                .isEqualTo(1);

        provider.steps(DeliveryResult.sent("slack-ts-manual"));
        assertThat(worker.process(messageId)).isEqualTo(MessageDeliveryWorker.AttemptResult.SENT);
        assertThat(status(messageId)).isEqualTo("SENT");
    }

    private UUID createMessage(Fixture fixture, String key) {
        IdempotencyResult result = send.send(
                fixture.caseId(),
                fixture.ownerId(),
                "send-" + key,
                "Support reply " + key,
                null,
                "corr-" + key);
        return UUID.fromString(result.body().get("messageId").asText());
    }

    private Fixture createFixture(String integrationStatus) {
        UUID ownerId = jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, 'Delivery owner', 'USER', TRUE)
                RETURNING id
                """, UUID.class, "delivery-" + UUID.randomUUID() + "@example.com");
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Delivery customer " + UUID.randomUUID(),
                "delivery-customer-" + UUID.randomUUID());
        UUID integrationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integrations (
                    id, provider, display_name, status, health, workspace_external_id
                ) VALUES (?, 'SLACK', 'Delivery Slack', ?, 'HEALTHY', ?)
                """, integrationId, integrationStatus, "T-" + integrationId);
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, customer_id, ignored,
                    grouping_strategy, active
                ) VALUES (?, ?, 'C-delivery', 'delivery', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                """, channelId, integrationId, customerId);
        UUID caseId = jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status,
                    owner_user_id, claimed_at
                ) VALUES (?, ?, ?, 'SLACK', 'C-delivery', 'thread-delivery',
                          'VERIFICATION', ?, CURRENT_TIMESTAMP)
                RETURNING id
                """, UUID.class, customerId, integrationId, channelId, ownerId);
        return new Fixture(ownerId, integrationId, caseId);
    }

    private String status(UUID messageId) {
        return jdbc.queryForObject(
                "SELECT delivery_status FROM messages WHERE id = ?", String.class, messageId);
    }

    private int countOutbox(UUID messageId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'message.send_requested' AND aggregate_id = ?",
                Integer.class,
                messageId);
    }

    private void makeRetryDue(UUID messageId) {
        jdbc.update("""
                UPDATE delivery_attempts
                SET next_retry_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE message_id = ? AND finished_at IS NOT NULL
                """, messageId);
    }

    private record Fixture(UUID ownerId, UUID integrationId, UUID caseId) {
    }

    @TestConfiguration
    static class FakeProviderConfiguration {
        @Bean
        FakeProvider fakeMessageDeliveryProvider() {
            return new FakeProvider();
        }
    }

    static final class FakeProvider implements MessageDeliveryProvider {
        private final ArrayDeque<DeliveryResult> steps = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean transactionSeen = new AtomicBoolean();
        private volatile String idempotencyKey;

        @Override
        public boolean supports(IntegrationProvider provider) {
            return provider == IntegrationProvider.SLACK;
        }

        @Override
        public DeliveryResult deliver(DeliveryCommand command) {
            calls.incrementAndGet();
            transactionSeen.compareAndSet(
                    false,
                    TransactionSynchronizationManager.isActualTransactionActive());
            idempotencyKey = command.idempotencyKey();
            DeliveryResult next = steps.pollFirst();
            if (next == null) throw new IllegalStateException("Fake provider has no configured result.");
            return next;
        }

        void steps(DeliveryResult... configured) {
            steps.clear();
            for (DeliveryResult result : configured) steps.addLast(result);
        }

        void reset() {
            steps.clear();
            calls.set(0);
            transactionSeen.set(false);
            idempotencyKey = null;
        }

        int calls() {
            return calls.get();
        }

        boolean sawDatabaseTransaction() {
            return transactionSeen.get();
        }

        String idempotencyKey() {
            return idempotencyKey;
        }
    }
}
