package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.InboundEventStore;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.sla.CaseSlaInitializer;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryCommand;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryResult;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import com.unifiedsupportinbox.provider.teams.internal.ControlledTeamsConnector;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import com.unifiedsupportinbox.workflow.CaseClaimService;
import com.unifiedsupportinbox.workflow.CaseResolveService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
@Import({MessageDeliveryWorkerIntegrationTests.FakeProviderConfiguration.class, ControlledTeamsConnector.Configuration.class})
class MessageDeliveryWorkerIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final String TEAMS_TENANT = "11111111-1111-4111-8111-111111111112";
    private static final String TEAMS_BOT = "11111111-1111-4111-8111-111111111113";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SupportSendMessageService send;
    @Autowired private MessageDeliveryService deliveries;
    @Autowired private MessageDeliveryWorker worker;
    @Autowired private FakeProvider provider;
    @Autowired private InboundEventStore inboundEvents;
    @Autowired private InboundMessageCommandHandler inboundMessages;
    @Autowired private CaseSlaInitializer sla;
    @Autowired private CaseClaimService claims;
    @Autowired private CaseResolveService resolutions;
    @Autowired private AskCustomerService legacyAsk;
    @Autowired private ControlledTeamsConnector teams;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private ConfiguredProviderSecretResolver secrets;

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
        registry.add("usi.providers.teams.outbound.enabled", () -> true);
        registry.add("usi.providers.teams.client-id", () -> TEAMS_BOT);
        registry.add("usi.providers.teams.tenant-id", () -> TEAMS_TENANT);
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
        teams.reset();
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
        assertThat(caseStatus(fixture)).isEqualTo("VERIFICATION");
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
        assertThat(caseStatus(fixture)).isEqualTo("VERIFICATION");
        assertThat(jdbc.queryForObject(
                "SELECT error_category FROM delivery_attempts WHERE message_id = ? AND attempt_no = 1",
                String.class, messageId)).isEqualTo("PERMANENT");
        assertThat(jdbc.queryForObject(
                "SELECT next_retry_at IS NULL FROM delivery_attempts WHERE message_id = ? AND attempt_no = 1",
                Boolean.class, messageId)).isTrue();
    }

    @Test
    void eighthTransientFailureExhaustsAutomaticAttempts() {
        Fixture fixture = createFixture("ENABLED");
        UUID messageId = createMessage(fixture, "attempt-limit");
        for (int attempt = 1; attempt <= 7; attempt++) {
            seedFinishedTransientAttempt(messageId, attempt, "PREVIOUS_TRANSIENT", "1 minute");
        }
        provider.steps(DeliveryResult.transientFailure("STILL_TRANSIENT", null));

        assertThat(worker.process(messageId)).isEqualTo(MessageDeliveryWorker.AttemptResult.FAILED);
        assertThat(status(messageId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM delivery_attempts WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT next_retry_at IS NULL FROM delivery_attempts WHERE message_id = ? AND attempt_no = 8",
                Boolean.class, messageId)).isTrue();
    }

    @Test
    void retryWindowOlderThanTwentyFourHoursStopsAutomaticRetry() {
        Fixture fixture = createFixture("ENABLED");
        UUID messageId = createMessage(fixture, "retry-window");
        seedFinishedTransientAttempt(messageId, 1, "OLD_TRANSIENT", "25 hours");
        provider.steps(DeliveryResult.transientFailure("TRANSIENT_AFTER_WINDOW", Duration.ofMillis(50)));

        assertThat(worker.process(messageId)).isEqualTo(MessageDeliveryWorker.AttemptResult.FAILED);
        assertThat(status(messageId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM delivery_attempts WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT next_retry_at IS NULL FROM delivery_attempts WHERE message_id = ? AND attempt_no = 2",
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

    @Test
    void retryWhileWaitingKeepsTheCurrentOwnerAndDeadline() {
        Fixture fixture = createFixture("ENABLED");
        provider.steps(DeliveryResult.sent("first"), DeliveryResult.sent("retried"));
        assertThat(worker.process(createMessage(fixture, "first"))).isEqualTo(MessageDeliveryWorker.AttemptResult.SENT);
        var deadline = waitingUntil(fixture);
        UUID failed = createMessage(fixture, "wait-retry");
        jdbc.update("UPDATE integrations SET status = 'DISABLED' WHERE id = ?", fixture.integrationId());
        assertThat(worker.process(failed)).isEqualTo(MessageDeliveryWorker.AttemptResult.FAILED);
        jdbc.update("UPDATE integrations SET status = 'ENABLED' WHERE id = ?", fixture.integrationId());
        assertThat(deliveries.retry(failed, fixture.ownerId(), "retry-waiting", "test").status()).isEqualTo(202);
        assertThat(waitingUntil(fixture)).isNotNull().isEqualTo(deadline);
        assertThat(worker.process(failed)).isEqualTo(MessageDeliveryWorker.AttemptResult.SENT);
        assertThat(caseStatus(fixture)).isEqualTo("WAITING_FOR_CUSTOMER");
        assertThat(caseOwner(fixture)).isEqualTo(fixture.ownerId());
        assertThat(waitingUntil(fixture)).isEqualTo(deadline);
    }

    @Test
    void teamsPostsConsecutiveRepliesToTheSameConversationAndDiscardsStaleWaiting() {
        Fixture fixture = createFixture("ENABLED", IntegrationProvider.TEAMS);
        assertThat(claims.claim(fixture.caseId(), fixture.ownerId(), "claim-teams", "teams").status()).isEqualTo(200);
        when(secrets.resolve("synthetic-teams", "teams-client-secret"))
                .thenAnswer(ignored -> Optional.of("synthetic-test-credential".getBytes(StandardCharsets.UTF_8)));
        java.time.OffsetDateTime deadline = null;
        for (int index = 1; index <= 3; index++) {
            UUID reply = createMessage(fixture, "teams-" + index);
            assertThat(worker.process(reply)).isEqualTo(MessageDeliveryWorker.AttemptResult.SENT);
            var post = teams.posts().get(index - 1);
            assertThat(post.path()).isEqualTo("/emea/v3/conversations/connector-conversation/activities/thread-delivery");
            assertThat(post.activity().path("id").asText()).isEqualTo(reply.toString());
            assertThat(post.activity().path("conversation").path("id").asText()).isEqualTo("connector-conversation");
            assertThat(post.activity().path("replyToId").asText()).isEqualTo("thread-delivery");
            assertThat(post.activity().path("text").asText()).isEqualTo("Support reply teams-" + index);
            assertThat(caseStatus(fixture)).isEqualTo("WAITING_FOR_CUSTOMER");
            assertThat(caseOwner(fixture)).isEqualTo(fixture.ownerId());
            if (index == 1) deadline = waitingUntil(fixture);
            assertThat(waitingUntil(fixture)).isNotNull().isEqualTo(deadline);
        }

        UUID queuedWhileWaiting = createMessage(fixture, "teams-stale");
        teams.beforeReceipt(() -> deliverInbound(fixture, IntegrationProvider.TEAMS, "teams-during-send"));
        assertThat(worker.process(queuedWhileWaiting)).isEqualTo(MessageDeliveryWorker.AttemptResult.SENT);
        assertThat(caseStatus(fixture)).isEqualTo("VERIFICATION");
        assertThat(caseOwner(fixture)).isEqualTo(fixture.ownerId());
        assertThat(waitingUntil(fixture)).isNull();

        teams.beforeReceipt(() -> {});
        assertThat(worker.process(createMessage(fixture, "teams-final"))).isEqualTo(MessageDeliveryWorker.AttemptResult.SENT);
        assertThat(resolutions.resolve(fixture.caseId(), fixture.ownerId(), "resolve-teams", null, "teams").status()).isEqualTo(200);
        assertThat(caseStatus(fixture)).isEqualTo("RESOLVED");
        assertThat(caseOwner(fixture)).isEqualTo(fixture.ownerId());
        assertThat(teams.posts()).hasSize(5);
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE case_id = ? AND action IN ('CASE_REPLY_WAITING', 'CASE_ASK_CUSTOMER')",
                String.class, fixture.caseId())).containsExactly("CASE_REPLY_WAITING", "CASE_REPLY_WAITING");
    }

    @Test
    void deprecatedAskReplaysIdempotentlyAndUsesTheOrdinaryOwnedWaitingDeadline() {
        Fixture fixture = createFixture("ENABLED");
        var queued = legacyAsk.ask(fixture.caseId(), fixture.ownerId(), "legacy-key", "Question", null, 60L, "test");
        assertThat(queued.status()).isEqualTo(202);
        assertThat(legacyAsk.ask(fixture.caseId(), fixture.ownerId(), "legacy-key", "Question", null, 60L, "test").body())
                .isEqualTo(queued.body());
        assertThat(caseStatus(fixture)).isEqualTo("VERIFICATION");
        UUID id = UUID.fromString(queued.body().path("messageId").asText());
        provider.steps(DeliveryResult.sent("legacy-alias-reply"));
        assertThat(worker.process(id)).isEqualTo(MessageDeliveryWorker.AttemptResult.SENT);
        assertThat(caseStatus(fixture)).isEqualTo("WAITING_FOR_CUSTOMER");
        assertThat(caseOwner(fixture)).isEqualTo(fixture.ownerId());
        assertThat(jdbc.queryForObject("""
                SELECT c.waiting_until = a.finished_at + interval '24 hours'
                FROM cases c JOIN delivery_attempts a ON a.message_id = ? WHERE c.id = ?
                """, Boolean.class, id, fixture.caseId())).isTrue();
    }

    @Test
    void intakeDuringProviderCallPreventsStaleWaitingAndTheNextIntakeOpensNrt() {
        Fixture fixture = createFixture("ENABLED");
        sla.initialize(fixture.caseId(), Instant.now().minusSeconds(120));
        UUID reply = createMessage(fixture, "concurrent-reply");
        provider.steps(DeliveryResult.sent("confirmed-race-reply"));
        provider.beforeResult(() -> deliverInbound(fixture, "during-send"));

        assertThat(worker.process(reply)).isEqualTo(MessageDeliveryWorker.AttemptResult.SENT);
        assertThat(jdbc.queryForObject("SELECT status FROM cases WHERE id = ?", String.class, fixture.caseId()))
                .isEqualTo("VERIFICATION");
        assertThat(jdbc.queryForObject("""
                SELECT first_response_completed_at = (
                    SELECT finished_at FROM delivery_attempts WHERE message_id = ?
                ) FROM case_sla WHERE case_id = ?
                """, Boolean.class, reply, fixture.caseId())).isTrue();
        assertThat(cycles(fixture)).isZero();

        UUID opening = deliverInbound(fixture, "after-confirmation");
        assertThat(cycles(fixture)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT opening_message_id FROM case_next_response_cycles WHERE case_id = ? AND ended_at IS NULL
                """, UUID.class, fixture.caseId())).isEqualTo(opening);
        assertThat(deliverInbound(fixture, "after-confirmation")).isEqualTo(opening);
        assertThat(cycles(fixture)).isEqualTo(1);
    }

    private int cycles(Fixture fixture) {
        return jdbc.queryForObject("SELECT count(*) FROM case_next_response_cycles WHERE case_id = ?",
                Integer.class, fixture.caseId());
    }
    @Test void providerAcceptanceWithoutMessageIdCompletesSlaOnlyAfterDeliveryAndCannotBeCountedTwice() {
        Fixture fixture=createFixture("ENABLED");sla.initialize(fixture.caseId(),Instant.now().minusSeconds(120));
        UUID reply=createMessage(fixture,"asynchronous-acceptance");provider.steps(DeliveryResult.sent(null));
        assertThat(jdbc.queryForObject("SELECT first_response_completed_at IS NULL FROM case_sla WHERE case_id=?",Boolean.class,fixture.caseId())).isTrue();
        assertThat(worker.process(reply)).isEqualTo(MessageDeliveryWorker.AttemptResult.SENT);
        var completion=jdbc.queryForObject("SELECT first_response_completed_at FROM case_sla WHERE case_id=?",java.time.OffsetDateTime.class,fixture.caseId());
        assertThat(completion).isNotNull();
        assertThat(jdbc.queryForObject("SELECT finished_at FROM delivery_attempts WHERE message_id=?",java.time.OffsetDateTime.class,reply)).isEqualTo(completion);
        assertThat(worker.process(reply)).isEqualTo(MessageDeliveryWorker.AttemptResult.NOT_CLAIMED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_attempts WHERE message_id=?",Integer.class,reply)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT first_response_completed_at FROM case_sla WHERE case_id=?",java.time.OffsetDateTime.class,fixture.caseId())).isEqualTo(completion);
        assertThat(provider.calls()).isEqualTo(1);
    }

    private UUID deliverInbound(Fixture fixture, String externalMessageId) {
        return deliverInbound(fixture, IntegrationProvider.SLACK, externalMessageId);
    }

    private UUID deliverInbound(Fixture fixture, IntegrationProvider provider, String externalMessageId) {
        UUID channel = jdbc.queryForObject("SELECT channel_id FROM cases WHERE id = ?", UUID.class, fixture.caseId());
        var event = inboundEvents.persistAuthenticated(provider.name(), fixture.integrationId(), "event-" + externalMessageId, "{}", "sla-race");
        return inboundMessages.handle(new InboundMessageCommandHandler.Command(
                event.id(), fixture.integrationId(), channel, provider, "event-" + externalMessageId,
                "C-delivery", externalMessageId, "thread-delivery", "customer", externalMessageId,
                InboundMessageCommandHandler.Mutation.CREATE, Instant.now(), "sla-race")).messageId();
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
        return createFixture(integrationStatus, IntegrationProvider.SLACK);
    }

    private Fixture createFixture(String integrationStatus, IntegrationProvider providerType) {
        boolean isTeams = providerType == IntegrationProvider.TEAMS;
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
                    id, provider, display_name, status, health, workspace_external_id, secret_ref
                ) VALUES (?, ?, 'Synthetic delivery', ?, 'HEALTHY', ?, ?)
                """, integrationId, providerType.name(), integrationStatus, isTeams ? TEAMS_TENANT : "T-" + integrationId,
                isTeams ? "synthetic-teams" : null);
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, customer_id, ignored,
                    grouping_strategy, active, metadata_json
                ) VALUES (?, ?, 'C-delivery', 'delivery', ?, FALSE, ?, TRUE, ?::jsonb)
                """, channelId, integrationId, customerId, isTeams ? "TEAMS_ROOT_REPLIES" : "SLACK_ROOT_THREAD",
                isTeams ? "{\"teamsContextType\":\"standardChannel\"}" : "{}");
        if (isTeams) {
            jdbc.update("""
                    INSERT INTO teams_conversation_routes (integration_id, external_channel_id, thread_key,
                        connector_conversation_id, service_url, bot_id, context_type)
                    VALUES (?, 'C-delivery', 'thread-delivery', 'connector-conversation',
                        'https://smba.trafficmanager.net/emea/', ?, 'standardChannel')
                    """, integrationId, TEAMS_BOT);
        }
        UUID caseId = jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status,
                    owner_user_id, claimed_at
                ) VALUES (?, ?, ?, ?, 'C-delivery', 'thread-delivery', ?, ?, ?)
                RETURNING id
                """, UUID.class, customerId, integrationId, channelId, providerType.name(),
                isTeams ? "NEW" : "VERIFICATION", isTeams ? null : ownerId, isTeams ? null : java.time.OffsetDateTime.now());
        return new Fixture(ownerId, integrationId, caseId);
    }

    private String status(UUID messageId) {
        return jdbc.queryForObject(
                "SELECT delivery_status FROM messages WHERE id = ?", String.class, messageId);
    }

    private String caseStatus(Fixture fixture) {
        return jdbc.queryForObject("SELECT status FROM cases WHERE id = ?", String.class, fixture.caseId());
    }

    private UUID caseOwner(Fixture fixture) {
        return jdbc.queryForObject("SELECT owner_user_id FROM cases WHERE id = ?", UUID.class, fixture.caseId());
    }

    private java.time.OffsetDateTime waitingUntil(Fixture fixture) {
        return jdbc.queryForObject("SELECT waiting_until FROM cases WHERE id = ?", java.time.OffsetDateTime.class, fixture.caseId());
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

    private void seedFinishedTransientAttempt(
            UUID messageId,
            int attemptNo,
            String errorCode,
            String age) {
        jdbc.update("""
                INSERT INTO delivery_attempts (
                    message_id, attempt_no, started_at, finished_at,
                    error_category, error_code, next_retry_at, provider_response_ref
                ) VALUES (
                    ?, ?, CURRENT_TIMESTAMP - CAST(? AS interval),
                    CURRENT_TIMESTAMP - CAST(? AS interval) + INTERVAL '1 second',
                    'TRANSIENT', ?, NULL, NULL
                )
                """, messageId, attemptNo, age, age, errorCode);
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
        private Runnable beforeResult = () -> {};

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
            beforeResult.run();
            DeliveryResult next = steps.pollFirst();
            if (next == null) throw new IllegalStateException("Fake provider has no configured result.");
            return next;
        }

        void steps(DeliveryResult... configured) {
            steps.clear();
            for (DeliveryResult result : configured) steps.addLast(result);
        }

        void beforeResult(Runnable action) { beforeResult = action; }

        void reset() {
            steps.clear();
            beforeResult = () -> {};
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
