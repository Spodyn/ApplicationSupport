package com.unifiedsupportinbox.readstate.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.readstate.CustomerMessageUnreadService;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class CustomerMessageUnreadDomainServiceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CustomerMessageUnreadService unread;
    private static TransactionTemplate transactions;
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
        unread = context.getBean(CustomerMessageUnreadService.class);
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        json = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void clearBusinessRows() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM case_read_states");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM cases");
        jdbc.update("DELETE FROM channels");
        jdbc.update("DELETE FROM integrations");
        jdbc.update("DELETE FROM customers");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void newCustomerMessageKeepsExistingCursorAndCreatesMissingEligibleRows() throws Exception {
        Fixture fixture = fixture("cursor");
        UUID existingUser = createUser("existing", true, null, null);
        UUID newlyEligibleUser = createUser("newly-eligible", true, null, null);
        UUID inactiveUser = createUser("inactive", false, null, null);
        UUID previousMessage = customerMessage(fixture.caseId(), "provider-previous", "previous", Instant.parse("2026-09-23T10:00:00Z"));
        UUID newMessage = customerMessage(fixture.caseId(), "provider-new", "new", Instant.parse("2026-09-23T10:01:00Z"));
        Instant readAt = Instant.parse("2026-09-23T10:00:30Z");

        jdbc.update("""
                INSERT INTO case_read_states (
                    user_id, case_id, last_read_message_id, last_read_at
                ) VALUES (?, ?, ?, ?)
                """, existingUser, fixture.caseId(), previousMessage, utc(readAt));

        transactions.executeWithoutResult(status -> unread.customerMessageCreated(
                fixture.caseId(), newMessage, "corr-new-message"));

        assertThat(jdbc.queryForObject("""
                SELECT last_read_message_id
                FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, UUID.class, existingUser, fixture.caseId())).isEqualTo(previousMessage);
        assertThat(jdbc.queryForObject("""
                SELECT last_read_message_id
                FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, UUID.class, newlyEligibleUser, fixture.caseId())).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, Integer.class, inactiveUser, fixture.caseId())).isZero();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE type = 'case.unread_changed' AND aggregate_id = ?
                """, Integer.class, fixture.caseId())).isEqualTo(1);
        String payload = jdbc.queryForObject("""
                SELECT payload_json::text FROM outbox_events
                WHERE type = 'case.unread_changed' AND aggregate_id = ?
                """, String.class, fixture.caseId());
        JsonNode event = json.readTree(payload);
        assertThat(event.get("caseId").stringValue()).isEqualTo(fixture.caseId().toString());
        assertThat(event.get("messageId").stringValue()).isEqualTo(newMessage.toString());
        assertThat(event.get("reason").stringValue()).isEqualTo("CUSTOMER_MESSAGE");
    }

    @Test
    void currentEligibilityIsAppliedForEveryNewCustomerMessage() {
        Fixture fixture = fixture("eligibility");
        UUID activeUser = createUser("active", true, null, null);
        UUID notYetValid = createUser("future", true, Instant.now().plusSeconds(3600), null);
        UUID expired = createUser("expired", true, null, Instant.now().minusSeconds(60));
        UUID message = customerMessage(fixture.caseId(), "provider-eligibility", "hello", Instant.now());

        transactions.executeWithoutResult(status -> unread.customerMessageCreated(
                fixture.caseId(), message, "corr-eligibility"));

        assertThat(readStateCount(activeUser, fixture.caseId())).isEqualTo(1);
        assertThat(readStateCount(notYetValid, fixture.caseId())).isZero();
        assertThat(readStateCount(expired, fixture.caseId())).isZero();
    }

    @Test
    void burstOfNewMessagesNeverMovesReadCursorBackward() {
        Fixture fixture = fixture("burst");
        UUID user = createUser("reader", true, null, null);
        UUID first = customerMessage(fixture.caseId(), "provider-burst-1", "one", Instant.parse("2026-09-23T11:00:00Z"));
        jdbc.update("""
                INSERT INTO case_read_states (user_id, case_id, last_read_message_id, last_read_at)
                VALUES (?, ?, ?, ?)
                """, user, fixture.caseId(), first, utc(Instant.parse("2026-09-23T11:00:10Z")));

        UUID second = customerMessage(fixture.caseId(), "provider-burst-2", "two", Instant.parse("2026-09-23T11:01:00Z"));
        UUID third = customerMessage(fixture.caseId(), "provider-burst-3", "three", Instant.parse("2026-09-23T11:02:00Z"));
        transactions.executeWithoutResult(status -> unread.customerMessageCreated(fixture.caseId(), second, "corr-burst-2"));
        transactions.executeWithoutResult(status -> unread.customerMessageCreated(fixture.caseId(), third, "corr-burst-3"));

        assertThat(jdbc.queryForObject("""
                SELECT last_read_message_id FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, UUID.class, user, fixture.caseId())).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE type = 'case.unread_changed' AND aggregate_id = ?
                """, Integer.class, fixture.caseId())).isEqualTo(2);
    }

    @Test
    void rejectsMessageThatIsNotAnInboundCustomerMessageAndWritesNoSignal() {
        Fixture fixture = fixture("wrong-message");
        UUID user = createUser("viewer", true, null, null);
        UUID supportMessage = supportMessage(fixture.caseId(), user);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> unread.customerMessageCreated(
                        fixture.caseId(), supportMessage, "corr-support")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_read_states", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'case.unread_changed'",
                Integer.class)).isZero();
    }

    private int readStateCount(UUID userId, UUID caseId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM case_read_states
                WHERE user_id = ? AND case_id = ?
                """, Integer.class, userId, caseId);
    }

    private static Fixture fixture(String label) {
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
                """, UUID.class, "Slack " + suffix, "workspace-" + suffix);
        UUID channelId = jdbc.queryForObject("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, ?, ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                RETURNING id
                """, UUID.class, integrationId, "channel-" + suffix, "support-" + suffix, customerId);
        UUID caseId = jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status
                ) VALUES (?, ?, ?, 'SLACK', ?, ?, 'NEW')
                RETURNING id
                """, UUID.class, customerId, integrationId, channelId, "conversation-" + suffix, "thread-" + suffix);
        return new Fixture(caseId);
    }

    private static UUID createUser(String label, boolean active, Instant validFrom, Instant validUntil) {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users (
                    email, display_name, role, active, valid_from, valid_until
                ) VALUES (?, ?, 'USER', ?, ?, ?)
                RETURNING id
                """, UUID.class,
                label + "-" + suffix + "@example.invalid",
                label,
                active,
                utc(validFrom),
                utc(validUntil));
    }

    private static UUID customerMessage(UUID caseId, String externalMessageId, String body, Instant occurredAt) {
        return jdbc.queryForObject("""
                INSERT INTO messages (
                    case_id, external_message_id, external_thread_key, kind,
                    author_external_id, body, body_format, inbound,
                    provider_created_at, correlation_id
                ) VALUES (?, ?, 'thread', 'CUSTOMER', 'customer', ?, 'PLAIN_TEXT', TRUE, ?, ?)
                RETURNING id
                """, UUID.class, caseId, externalMessageId, body, utc(occurredAt), "corr-" + UUID.randomUUID());
    }

    private static UUID supportMessage(UUID caseId, UUID authorUserId) {
        return jdbc.queryForObject("""
                INSERT INTO messages (
                    case_id, external_thread_key, kind, author_user_id,
                    body, body_format, inbound, delivery_status, correlation_id
                ) VALUES (?, 'thread', 'SUPPORT', ?, 'reply', 'PLAIN_TEXT', FALSE, 'QUEUED', ?)
                RETURNING id
                """, UUID.class, caseId, authorUserId, "corr-" + UUID.randomUUID());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Fixture(UUID caseId) {
    }
}
