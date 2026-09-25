package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class SupportSendMessageServiceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Autowired
    private SupportSendMessageService service;

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
    void currentOwnerCreatesQueuedMessageAndOutboxAtomically() {
        UUID ownerId = createUser("owner");
        UUID caseId = createCase(ownerId, "VERIFICATION");

        IdempotencyResult result = service.send(
                caseId,
                ownerId,
                "send-key-1",
                "Hello from support",
                MessageBodyFormat.PLAIN_TEXT,
                "corr-send-1");

        assertThat(result.status()).isEqualTo(202);
        assertThat(result.replayed()).isFalse();
        UUID messageId = UUID.fromString(result.body().get("messageId").asText());
        assertThat(result.body().get("deliveryStatus").asText()).isEqualTo("QUEUED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT delivery_status FROM messages WHERE id = ?", String.class, messageId))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT author_user_id FROM messages WHERE id = ?", UUID.class, messageId))
                .isEqualTo(ownerId);
        assertThat(jdbc.queryForObject(
                "SELECT external_thread_key FROM messages WHERE id = ?", String.class, messageId))
                .isEqualTo("thread-1");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'message.send_requested' AND aggregate_id = ?",
                Integer.class,
                messageId)).isEqualTo(1);
    }

    @Test
    void duplicateClickReplaysSameMessageWithoutDuplicateOutbox() {
        UUID ownerId = createUser("owner-replay");
        UUID caseId = createCase(ownerId, "VERIFICATION");

        IdempotencyResult first = service.send(
                caseId, ownerId, "same-key", "Same reply", null, "corr-first");
        IdempotencyResult replay = service.send(
                caseId, ownerId, "same-key", "Same reply", null, "corr-replay");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'message.send_requested'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void nonOwnerCannotSend() {
        UUID ownerId = createUser("owner-denied");
        UUID anotherUser = createUser("other-denied");
        UUID caseId = createCase(ownerId, "VERIFICATION");

        assertThatThrownBy(() -> service.send(
                caseId, anotherUser, "denied-key", "Nope", null, "corr-denied"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.status()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'message.send_requested'", Integer.class))
                .isZero();
    }

    @Test
    void ownerCannotSendOutsideVerification() {
        UUID ownerId = createUser("owner-resolved");
        UUID caseId = createCase(ownerId, "RESOLVED");

        assertThatThrownBy(() -> service.send(
                caseId, ownerId, "resolved-key", "Nope", null, "corr-resolved"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
    }

    @Test
    void sendIsRejectedForUnclaimedWaitingAndTerminalCases() {
        UUID userId = createUser("owner-invalid-state");
        for (String status : new String[] {"NEW", "WAITING_FOR_CUSTOMER", "IGNORED", "RESOLVED"}) {
            UUID caseId = createCase(
                    status.equals("RESOLVED") ? userId : null,
                    status);
            assertThatThrownBy(() -> service.send(
                    caseId, userId, "invalid-state-" + status, "Nope", null, "corr-" + status))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            problem -> assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'message.send_requested'", Integer.class)).isZero();
    }

    private UUID createUser(String prefix) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, ?, 'USER', TRUE)
                RETURNING id
                """, UUID.class,
                prefix + "-" + UUID.randomUUID() + "@example.com",
                prefix);
    }

    private UUID createCase(UUID ownerId, String status) {
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Customer " + UUID.randomUUID(),
                "customer-" + UUID.randomUUID());
        UUID integrationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integrations (
                    id, provider, display_name, status, health, workspace_external_id
                ) VALUES (?, 'SLACK', 'Test Slack', 'ENABLED', 'HEALTHY', ?)
                """, integrationId, "T-" + integrationId);
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, customer_id, ignored,
                    grouping_strategy, active
                ) VALUES (?, ?, 'C-support', 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                """, channelId, integrationId, customerId);

        return jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status,
                    owner_user_id, claimed_at, waiting_until, resolved_at, ignored_at
                ) VALUES (
                    ?, ?, ?, 'SLACK', 'C-support', 'thread-1', ?, ?, CURRENT_TIMESTAMP,
                    CASE WHEN ? = 'WAITING_FOR_CUSTOMER' THEN CURRENT_TIMESTAMP + INTERVAL '1 hour' ELSE NULL END,
                    CASE WHEN ? = 'RESOLVED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    CASE WHEN ? = 'IGNORED' THEN CURRENT_TIMESTAMP ELSE NULL END
                )
                RETURNING id
                """, UUID.class,
                customerId,
                integrationId,
                channelId,
                status,
                ownerId,
                status,
                status,
                status);
    }
}
