package com.unifiedsupportinbox.provider.telegram.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.testing.TestInfrastructure;
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
class TelegramChatCaseMappingIntegrationTests {

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
    void subsequentMessagesWithoutTopicAppendToOneActiveCaseForChat() {
        Fixture fixture = mappedFixture("90019101");
        InboundEvent first = persist(fixture, 191001, 101, "first");
        InboundEvent second = persist(fixture, 191002, 102, "second");

        worker.process(first.id());
        worker.process(second.id());

        UUID caseId = caseIdForMessage("101");
        assertThat(caseIdForMessage("102")).isEqualTo(caseId);
        assertThat(count("cases")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(2);
        assertThat(countOutbox("case.created")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT external_conversation_id FROM cases WHERE id = ?",
                String.class,
                caseId)).isEqualTo(fixture.externalChatId());
        assertThat(jdbc.queryForObject(
                "SELECT external_thread_key FROM cases WHERE id = ?",
                String.class,
                caseId)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT external_thread_key FROM messages WHERE external_message_id = '102'",
                String.class)).isNull();
    }

    @Test
    void nextMessageAfterTerminalCaseCreatesLinkedChatSuccessor() {
        Fixture fixture = mappedFixture("90019102");
        InboundEvent originalMessage = persist(fixture, 191101, 201, "first generation");
        worker.process(originalMessage.id());
        UUID originalCaseId = caseIdForMessage("201");
        jdbc.update("""
                UPDATE cases
                SET status = 'IGNORED',
                    ignored_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, originalCaseId);

        InboundEvent successorMessage = persist(fixture, 191102, 202, "next generation");
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
                originalCaseId)).isEqualTo("IGNORED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?",
                String.class,
                successorCaseId)).isEqualTo("NEW");
        assertThat(count("cases")).isEqualTo(2);
        assertThat(countOutbox("case.created")).isEqualTo(2);
    }

    @Test
    void concurrentMessagesForSameChatConvergeOnOneActiveCase() throws Exception {
        Fixture fixture = mappedFixture("90019103");
        InboundEvent first = persist(fixture, 191201, 301, "racing first");
        InboundEvent second = persist(fixture, 191202, 302, "racing second");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstAttempt = executor.submit(() -> processTogether(first.id(), ready, start));
            Future<?> secondAttempt = executor.submit(() -> processTogether(second.id(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstAttempt.get(20, TimeUnit.SECONDS);
            secondAttempt.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(caseIdForMessage("301")).isEqualTo(caseIdForMessage("302"));
        assertThat(count("cases")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(2);
        assertThat(countOutbox("case.created")).isEqualTo(1);
    }

    @Test
    void duplicateUpdateIsProcessedOnceWithoutDuplicateCaseOrMessage() {
        Fixture fixture = mappedFixture("90019104");
        InboundEvent first = persist(fixture, 191301, 401, "one update");
        InboundEvent duplicate = persist(fixture, 191301, 401, "one update");

        assertThat(duplicate.id()).isEqualTo(first.id());
        worker.process(first.id());
        worker.process(duplicate.id());

        assertThat(count("inbound_events")).isEqualTo(1);
        assertThat(count("cases")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(1);
        assertThat(countOutbox("case.created")).isEqualTo(1);
    }

    private void processTogether(UUID eventId, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Telegram chat contenders did not start together.");
            }
            worker.process(eventId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Telegram chat contender was interrupted.", interrupted);
        }
    }

    private Fixture mappedFixture(String externalChatId) {
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
        jdbc.update("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, ?, ?, FALSE, 'TELEGRAM_CHAT_ACTIVE_CASE', TRUE)
                """,
                integrationId,
                externalChatId,
                "chat-" + suffix,
                customerId);
        return new Fixture(integrationId, externalChatId);
    }

    private InboundEvent persist(
            Fixture fixture,
            long updateId,
            long messageId,
            String text) {
        return deliveries.persistAndWake(
                fixture.integrationId(),
                Long.toString(updateId),
                chatMessage(updateId, messageId, fixture.externalChatId(), text),
                "corr-" + updateId);
    }

    private static String chatMessage(
            long updateId,
            long messageId,
            String chatId,
            String text) {
        return """
                {
                  "update_id": %d,
                  "message": {
                    "message_id": %d,
                    "date": 1720000000,
                    "chat": {"id": %s, "type": "private"},
                    "from": {"id": 9001, "is_bot": false},
                    "text": "%s"
                  }
                }
                """.formatted(updateId, messageId, chatId, text);
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

    private record Fixture(UUID integrationId, String externalChatId) {
    }
}
