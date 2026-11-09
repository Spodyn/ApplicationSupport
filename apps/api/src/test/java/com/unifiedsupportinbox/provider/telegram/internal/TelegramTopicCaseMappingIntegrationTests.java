package com.unifiedsupportinbox.provider.telegram.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.InboundEventOutcomeStore;
import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        properties = "usi.telegram.inbound-worker.enabled=false")
@ActiveProfiles("test")
class TelegramTopicCaseMappingIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Autowired
    private TelegramInboundDeliveryService deliveries;

    @Autowired
    private TelegramInboundWorker worker;

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
    void topicMessagesUseChatAndMessageThreadAsOneActiveCaseContext() {
        Fixture fixture = mappedFixture("-10019001", false);

        InboundEvent first = persist(fixture, 19001, 101, 7001, "topic root");
        InboundEvent reply = persist(fixture, 19002, 102, 7001, "topic reply");
        InboundEvent otherTopic = persist(fixture, 19003, 103, 7002, "other topic");

        worker.process(first.id());
        worker.process(reply.id());
        worker.process(otherTopic.id());

        UUID firstCaseId = caseIdForMessage("101");
        assertThat(caseIdForMessage("102")).isEqualTo(firstCaseId);
        assertThat(caseIdForMessage("103")).isNotEqualTo(firstCaseId);
        assertThat(count("cases")).isEqualTo(2);
        assertThat(count("messages")).isEqualTo(3);
        assertThat(countOutbox("case.created")).isEqualTo(2);

        Map<String, Object> firstCase = jdbc.queryForMap("""
                SELECT provider, external_conversation_id, external_thread_key, status
                FROM cases
                WHERE id = ?
                """, firstCaseId);
        assertThat(firstCase.get("provider")).isEqualTo("TELEGRAM");
        assertThat(firstCase.get("external_conversation_id")).isEqualTo(fixture.externalChatId());
        assertThat(firstCase.get("external_thread_key")).isEqualTo("7001");
        assertThat(firstCase.get("status")).isEqualTo("NEW");
    }

    @Test
    void customerMessageAfterTerminalCaseCreatesLinkedSuccessorInSameTopic() {
        Fixture fixture = mappedFixture("-10019002", false);
        InboundEvent originalMessage = persist(fixture, 19101, 201, 7101, "first generation");
        worker.process(originalMessage.id());
        UUID originalCaseId = caseIdForMessage("201");
        jdbc.update("""
                UPDATE cases
                SET status = 'RESOLVED',
                    resolved_at = CURRENT_TIMESTAMP,
                    resolution_category = 'SOLVED'
                WHERE id = ?
                """, originalCaseId);

        InboundEvent successorMessage = persist(fixture, 19102, 202, 7101, "next generation");
        worker.process(successorMessage.id());

        UUID successorCaseId = caseIdForMessage("202");
        assertThat(successorCaseId).isNotEqualTo(originalCaseId);
        assertThat(jdbc.queryForObject(
                "SELECT related_case_id FROM cases WHERE id = ?",
                UUID.class,
                successorCaseId)).isEqualTo(originalCaseId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?",
                String.class,
                originalCaseId)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?",
                String.class,
                successorCaseId)).isEqualTo("NEW");
        assertThat(count("cases")).isEqualTo(2);
        assertThat(countOutbox("case.created")).isEqualTo(2);
    }

    @Test
    void racingDuplicateTopicDeliveriesConvergeOnOneCaseAndMessage() throws Exception {
        Fixture fixture = mappedFixture("-10019003", false);
        InboundEvent first = persist(fixture, 19201, 301, 7201, "same provider message");
        InboundEvent duplicate = persist(fixture, 19202, 301, 7201, "same provider message");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstAttempt = executor.submit(() -> processTogether(first.id(), ready, start));
            Future<?> duplicateAttempt = executor.submit(() -> processTogether(duplicate.id(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstAttempt.get(20, TimeUnit.SECONDS);
            duplicateAttempt.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(count("cases")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(1);
        assertThat(countOutbox("case.created")).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT status FROM inbound_events WHERE id IN (?, ?)",
                String.class,
                first.id(),
                duplicate.id())).containsExactlyInAnyOrder("PROCESSED", "PROCESSED");
    }

    @Test
    void ignoredTopicChatRecordsTechnicalOutcomeWithoutBusinessSideEffects() {
        Fixture fixture = mappedFixture("-10019004", true);
        InboundEvent ignored = persist(fixture, 19301, 401, 7301, "ignored");

        worker.process(ignored.id());

        assertThat(count("cases")).isZero();
        assertThat(count("messages")).isZero();
        assertThat(countOutbox("case.created")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT processing_outcome FROM inbound_events WHERE id = ?",
                String.class,
                ignored.id())).isEqualTo(InboundEventOutcomeStore.IGNORED_BY_CHANNEL);
    }

    private void processTogether(UUID eventId, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Telegram topic contenders did not start together.");
            }
            worker.process(eventId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Telegram topic contender was interrupted.", interrupted);
        }
    }

    private Fixture mappedFixture(String externalChatId, boolean ignored) {
        String suffix = UUID.randomUUID().toString();
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Telegram customer " + suffix,
                "telegram-customer-" + suffix);
        UUID integrationId = jdbc.queryForObject("""
                INSERT INTO integrations (
                    provider, display_name, status, health, workspace_external_id
                ) VALUES ('TELEGRAM', ?, 'ENABLED', 'HEALTHY', ?)
                RETURNING id
                """,
                UUID.class,
                "Telegram " + suffix,
                "bot-" + suffix);
        UUID channelId = jdbc.queryForObject("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, ?, ?, ?, 'TELEGRAM_TOPIC', TRUE)
                RETURNING id
                """,
                UUID.class,
                integrationId,
                externalChatId,
                "forum-" + suffix,
                customerId,
                ignored);
        return new Fixture(integrationId, channelId, externalChatId);
    }

    private InboundEvent persist(
            Fixture fixture,
            long updateId,
            long messageId,
            long topicId,
            String text) {
        return deliveries.persistAndWake(
                fixture.integrationId(),
                Long.toString(updateId),
                topicMessage(updateId, messageId, topicId, fixture.externalChatId(), text),
                "corr-" + updateId);
    }

    private static String topicMessage(
            long updateId,
            long messageId,
            long topicId,
            String chatId,
            String text) {
        return """
                {
                  "update_id": %d,
                  "message": {
                    "message_id": %d,
                    "message_thread_id": %d,
                    "date": 1720000000,
                    "chat": {"id": %s, "type": "supergroup", "is_forum": true},
                    "from": {"id": 9001, "is_bot": false},
                    "is_topic_message": true,
                    "text": "%s"
                  }
                }
                """.formatted(updateId, messageId, topicId, chatId, text);
    }

    private UUID caseIdForMessage(String externalMessageId) {
        return jdbc.queryForObject(
                "SELECT case_id FROM messages WHERE external_message_id = ?",
                UUID.class,
                externalMessageId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countOutbox(String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = ?",
                Integer.class,
                type);
    }

    private record Fixture(UUID integrationId, UUID channelId, String externalChatId) {
    }
}
