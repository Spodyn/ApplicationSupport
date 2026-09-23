package com.unifiedsupportinbox.readstate.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class CaseCreatedUnreadProjectionIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final Instant CASE_CREATED_AT = Instant.parse("2026-09-23T12:00:00Z");

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseCreatedUnreadProjection projection;
    private static ObjectMapper json;

    @BeforeAll
    static void startApplication() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.session.jdbc.initialize-schema=never",
                        "--usi.bootstrap-admin.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        projection = context.getBean(CaseCreatedUnreadProjection.class);
        json = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void clearBusinessRows() {
        jdbc.update("DELETE FROM case_read_states");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM cases");
        jdbc.update("DELETE FROM channels");
        jdbc.update("DELETE FROM integrations");
        jdbc.update("DELETE FROM customers");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void initializesUnreadOnlyForEligibleUsersThatExistedAtCaseCreation() {
        UUID activeUser = createUser(
                "active-user",
                "USER",
                true,
                CASE_CREATED_AT.minusSeconds(3600),
                null,
                null);
        UUID activeAdmin = createUser(
                "active-admin",
                "ADMIN",
                true,
                CASE_CREATED_AT.minusSeconds(7200),
                CASE_CREATED_AT.minusSeconds(3600),
                CASE_CREATED_AT.plusSeconds(3600));
        createUser(
                "inactive-user",
                "USER",
                false,
                CASE_CREATED_AT.minusSeconds(3600),
                null,
                null);
        createUser(
                "not-yet-valid",
                "USER",
                true,
                CASE_CREATED_AT.minusSeconds(3600),
                CASE_CREATED_AT.plusSeconds(60),
                null);
        createUser(
                "expired-user",
                "USER",
                true,
                CASE_CREATED_AT.minusSeconds(3600),
                null,
                CASE_CREATED_AT.minusSeconds(60));
        createUser(
                "created-later",
                "USER",
                true,
                CASE_CREATED_AT.plusSeconds(60),
                null,
                null);
        UUID caseId = createCase("eligibility", CASE_CREATED_AT);

        assertThat(projection.initialize(caseId)).isEqualTo(2);

        List<UUID> projectedUsers = jdbc.queryForList(
                "SELECT user_id FROM case_read_states WHERE case_id = ? ORDER BY user_id",
                UUID.class,
                caseId);
        assertThat(projectedUsers).containsExactlyInAnyOrder(activeUser, activeAdmin);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM case_read_states
                WHERE case_id = ?
                  AND last_read_message_id IS NULL
                  AND last_read_at IS NULL
                """, Integer.class, caseId)).isEqualTo(2);
    }

    @Test
    void duplicateCaseCreatedDoesNotOverwriteANewerReadPositionOrBackfillLaterUsers() {
        UUID existingUser = createUser(
                "existing-user",
                "USER",
                true,
                CASE_CREATED_AT.minusSeconds(3600),
                null,
                null);
        UUID caseId = createCase("duplicate", CASE_CREATED_AT);

        assertThat(projection.initialize(caseId)).isEqualTo(1);
        UUID messageId = customerMessage(caseId, "provider-duplicate", CASE_CREATED_AT.plusSeconds(30));
        Instant readAt = CASE_CREATED_AT.plusSeconds(60);
        jdbc.update("""
                UPDATE case_read_states
                SET last_read_message_id = ?, last_read_at = ?, updated_at = ?
                WHERE user_id = ? AND case_id = ?
                """,
                messageId,
                utc(readAt),
                utc(readAt),
                existingUser,
                caseId);

        UUID laterUser = createUser(
                "later-user",
                "USER",
                true,
                CASE_CREATED_AT.plusSeconds(120),
                null,
                null);

        assertThat(projection.initialize(caseId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT last_read_message_id
                FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, UUID.class, existingUser, caseId)).isEqualTo(messageId);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, Integer.class, laterUser, caseId)).isZero();
    }

    @Test
    void transientProjectionFailureCanBeRetriedWithoutPartialRows() {
        UUID userId = createUser(
                "retry-user",
                "USER",
                true,
                CASE_CREATED_AT.minusSeconds(3600),
                null,
                null);
        UUID caseId = createCase("retry", CASE_CREATED_AT);

        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_case_read_state_for_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced unread projection failure';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_case_read_state_for_test_trigger
                BEFORE INSERT ON case_read_states
                FOR EACH ROW
                EXECUTE FUNCTION reject_case_read_state_for_test()
                """);

        try {
            assertThatThrownBy(() -> projection.initialize(caseId))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM case_read_states WHERE case_id = ?",
                    Integer.class,
                    caseId)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_case_read_state_for_test_trigger ON case_read_states");
            jdbc.execute("DROP FUNCTION IF EXISTS reject_case_read_state_for_test()");
        }

        assertThat(projection.initialize(caseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, Integer.class, userId, caseId)).isEqualTo(1);
    }

    @Test
    void malformedOrUnknownCaseCreatedIsRejectedWithoutRequeueAndQueueHasDlq() {
        CaseCreatedUnreadRabbitListener listener = new CaseCreatedUnreadRabbitListener(projection, json);

        assertThatThrownBy(() -> listener.onCaseCreated(new Message("{}".getBytes(UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.onCaseCreated(new Message(
                        ("{\"caseId\":\"" + UUID.randomUUID() + "\"}").getBytes(UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        var queue = new CaseCreatedUnreadRabbitConfiguration().caseCreatedUnreadQueue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", CaseCreatedUnreadRabbitConfiguration.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", CaseCreatedUnreadRabbitConfiguration.DEAD_LETTER_ROUTING_KEY);
    }

    private static UUID createUser(
            String label,
            String role,
            boolean active,
            Instant createdAt,
            Instant validFrom,
            Instant validUntil) {
        return jdbc.queryForObject("""
                INSERT INTO users (
                    email, display_name, role, active,
                    valid_from, valid_until, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                UUID.class,
                label + "-" + UUID.randomUUID() + "@example.invalid",
                label,
                role,
                active,
                utc(validFrom),
                utc(validUntil),
                utc(createdAt),
                utc(createdAt));
    }

    private static UUID createCase(String label, Instant createdAt) {
        String suffix = label + "-" + UUID.randomUUID();
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Customer " + suffix,
                "customer-" + suffix);
        UUID integrationId = jdbc.queryForObject("""
                INSERT INTO integrations (
                    provider, display_name, status, health, workspace_external_id
                ) VALUES ('SLACK', ?, 'ENABLED', 'HEALTHY', ?)
                RETURNING id
                """,
                UUID.class,
                "Slack " + suffix,
                "workspace-" + suffix);
        UUID channelId = jdbc.queryForObject("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, ?, ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                RETURNING id
                """,
                UUID.class,
                integrationId,
                "channel-" + suffix,
                "support-" + suffix,
                customerId);
        return jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status,
                    created_at, updated_at, last_activity_at
                )
                VALUES (?, ?, ?, 'SLACK', ?, ?, 'NEW', ?, ?, ?)
                RETURNING id
                """,
                UUID.class,
                customerId,
                integrationId,
                channelId,
                "conversation-" + suffix,
                "thread-" + suffix,
                utc(createdAt),
                utc(createdAt),
                utc(createdAt));
    }

    private static UUID customerMessage(UUID caseId, String externalMessageId, Instant providerCreatedAt) {
        return jdbc.queryForObject("""
                INSERT INTO messages (
                    case_id, external_message_id, external_thread_key, kind,
                    author_external_id, body, body_format, inbound,
                    provider_created_at, correlation_id
                )
                VALUES (?, ?, 'thread', 'CUSTOMER', 'customer', 'hello', 'PLAIN_TEXT', TRUE, ?, ?)
                RETURNING id
                """,
                UUID.class,
                caseId,
                externalMessageId,
                utc(providerCreatedAt),
                "corr-" + UUID.randomUUID());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
