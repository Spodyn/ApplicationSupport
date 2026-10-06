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
class AskCustomerServiceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Autowired private AskCustomerService service;
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
    void ownerQueuesAskButCaseRemainsOwnedUntilProviderSuccess() {
        UUID ownerId = createUser("ask-owner");
        UUID caseId = createCase(ownerId);

        IdempotencyResult result = service.ask(
                caseId,
                ownerId,
                "ask-key-1",
                "Could you confirm the transaction?",
                MessageBodyFormat.PLAIN_TEXT,
                null,
                "corr-ask-1");

        assertThat(result.status()).isEqualTo(202);
        assertThat(result.replayed()).isFalse();
        UUID messageId = UUID.fromString(result.body().get("messageId").asText());

        assertThat(jdbc.queryForObject("SELECT status FROM cases WHERE id = ?", String.class, caseId))
                .isEqualTo("VERIFICATION");
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM cases WHERE id = ?", UUID.class, caseId))
                .isEqualTo(ownerId);
        assertThat(jdbc.queryForObject("SELECT waiting_until FROM cases WHERE id = ?", Object.class, caseId))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT ask_waiting_seconds FROM messages WHERE id = ?", Long.class, messageId))
                .isEqualTo(86_400L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'message.send_requested' AND aggregate_id = ?",
                Integer.class,
                messageId)).isEqualTo(1);
    }

    @Test
    void duplicateCommandReplaysSameLogicalAskAndCustomWaitIsDurable() {
        UUID ownerId = createUser("ask-replay");
        UUID caseId = createCase(ownerId);

        IdempotencyResult first = service.ask(
                caseId, ownerId, "same-ask-key", "Need details", null, 180L, "corr-first");
        IdempotencyResult replay = service.ask(
                caseId, ownerId, "same-ask-key", "Need details", null, 180L, "corr-replay");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(first.body());
        UUID messageId = UUID.fromString(first.body().get("messageId").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT ask_waiting_seconds FROM messages WHERE id = ?", Long.class, messageId))
                .isEqualTo(10_800L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'message.send_requested'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void nonOwnerBlankMessageAndInvalidWaitAreRejectedWithoutMessage() {
        UUID ownerId = createUser("ask-owner-denied");
        UUID otherId = createUser("ask-other");
        UUID caseId = createCase(ownerId);

        assertThatThrownBy(() -> service.ask(
                caseId, otherId, "other-key", "Nope", null, null, "corr-other"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> service.ask(
                caseId, ownerId, "blank-key", "   ", null, null, "corr-blank"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.ask(
                caseId, ownerId, "short-wait", "Question", null, 59L, "corr-short"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.ask(
                caseId, ownerId, "long-wait", "Question", null, 43_201L, "corr-long"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
    }

    private UUID createUser(String prefix) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, ?, 'USER', TRUE)
                RETURNING id
                """, UUID.class, prefix + "-" + UUID.randomUUID() + "@example.com", prefix);
    }

    private UUID createCase(UUID ownerId) {
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Ask customer " + UUID.randomUUID(),
                "ask-customer-" + UUID.randomUUID());
        UUID integrationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integrations (
                    id, provider, display_name, status, health, workspace_external_id
                ) VALUES (?, 'SLACK', 'Ask Slack', 'ENABLED', 'HEALTHY', ?)
                """, integrationId, "T-" + integrationId);
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, customer_id, ignored,
                    grouping_strategy, active
                ) VALUES (?, ?, 'C-ask', 'ask', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                """, channelId, integrationId, customerId);
        return jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status,
                    owner_user_id, claimed_at
                ) VALUES (?, ?, ?, 'SLACK', 'C-ask', 'thread-ask', 'VERIFICATION', ?, CURRENT_TIMESTAMP)
                RETURNING id
                """, UUID.class, customerId, integrationId, channelId, ownerId);
    }
}
