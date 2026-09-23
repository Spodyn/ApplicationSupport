package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.messaging.MessageDeliveryStatus;
import com.unifiedsupportinbox.messaging.MessageKind;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class MessagePersistenceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static MessageRepository repository;

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
        repository = context.getBean(MessageRepository.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void clearMessages() {
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM cases");
    }

    @Test
    void persistsCanonicalCustomerSupportAndSystemMessagesWithoutMutatingBody() {
        Fixture fixture = fixture();
        UUID supportUserId = createUser();
        String markdown = "Zażółć gęślą 🧪\n\n```json\n{\"status\":\"ok\"}\n```";
        Instant providerTime = Instant.parse("2026-09-23T10:15:30.123456Z");

        MessageEntity customer = repository.saveAndFlush(new MessageEntity(
                fixture.caseId(),
                "1710000000.000001",
                "1710000000.000001",
                MessageKind.CUSTOMER,
                null,
                "U-customer",
                "Jan Klient",
                markdown,
                MessageBodyFormat.MARKDOWN,
                true,
                null,
                providerTime,
                null,
                null,
                "corr-customer"));

        MessageEntity support = repository.saveAndFlush(new MessageEntity(
                fixture.caseId(),
                null,
                "1710000000.000001",
                MessageKind.SUPPORT,
                supportUserId,
                null,
                "Agent",
                "Dzień dobry — sprawdzam to.",
                MessageBodyFormat.PLAIN_TEXT,
                false,
                MessageDeliveryStatus.QUEUED,
                null,
                null,
                null,
                "corr-support"));

        MessageEntity system = repository.saveAndFlush(new MessageEntity(
                fixture.caseId(),
                null,
                null,
                MessageKind.SYSTEM,
                null,
                null,
                "System",
                "Widoczna informacja systemowa",
                MessageBodyFormat.PLAIN_TEXT,
                false,
                null,
                null,
                null,
                null,
                "corr-system"));

        assertThat(customer.id()).isNotNull();
        assertThat(customer.id().version()).isEqualTo(7);
        assertThat(customer.body()).isEqualTo(markdown);
        assertThat(customer.providerCreatedAt()).isEqualTo(providerTime);
        assertThat(customer.inbound()).isTrue();
        assertThat(customer.deliveryStatus()).isNull();
        assertThat(customer.createdAt()).isNotNull();

        assertThat(support.kind()).isEqualTo(MessageKind.SUPPORT);
        assertThat(support.authorUserId()).isEqualTo(supportUserId);
        assertThat(support.deliveryStatus()).isEqualTo(MessageDeliveryStatus.QUEUED);

        assertThat(system.kind()).isEqualTo(MessageKind.SYSTEM);
        assertThat(system.deliveryStatus()).isNull();
    }

    @Test
    void providerMessageIdentityIsDeduplicatedWithinCase() {
        Fixture fixture = fixture();
        Instant occurredAt = Instant.parse("2026-09-23T11:00:00Z");

        repository.saveAndFlush(customerMessage(
                fixture.caseId(), "provider-42", "thread-1", "first", occurredAt, "corr-1"));

        assertThatThrownBy(() -> repository.saveAndFlush(customerMessage(
                        fixture.caseId(), "provider-42", "thread-1", "duplicate", occurredAt, "corr-2")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE case_id = ? AND external_message_id = ?",
                Integer.class,
                fixture.caseId(),
                "provider-42"))
                .isEqualTo(1);
    }

    @Test
    void providerOrderingIsStableAndUsesProviderTimestampThenId() {
        Fixture fixture = fixture();

        MessageEntity later = repository.saveAndFlush(customerMessage(
                fixture.caseId(),
                "m-later",
                "thread-order",
                "later",
                Instant.parse("2026-09-23T12:00:02Z"),
                "corr-later"));
        MessageEntity earlier = repository.saveAndFlush(customerMessage(
                fixture.caseId(),
                "m-earlier",
                "thread-order",
                "earlier",
                Instant.parse("2026-09-23T12:00:01Z"),
                "corr-earlier"));

        List<MessageEntity> ordered = repository.findByCaseIdOrderByProviderCreatedAtAscIdAsc(fixture.caseId());

        assertThat(ordered).extracting(MessageEntity::id).containsExactly(earlier.id(), later.id());
    }

    @Test
    void databaseEnforcesAuthorshipDirectionDeliveryAndForeignKeys() {
        Fixture fixture = fixture();
        UUID supportUserId = createUser();

        assertThatThrownBy(() -> rawInsert(
                        fixture.caseId(),
                        "bad-customer-direction",
                        "CUSTOMER",
                        false,
                        null,
                        "U-customer",
                        null,
                        "corr-bad-1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> rawInsert(
                        fixture.caseId(),
                        "bad-support-author",
                        "SUPPORT",
                        false,
                        null,
                        null,
                        "QUEUED",
                        "corr-bad-2"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> rawInsert(
                        fixture.caseId(),
                        "bad-system-author",
                        "SYSTEM",
                        false,
                        supportUserId,
                        null,
                        null,
                        "corr-bad-3"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> rawInsert(
                        UUID.randomUUID(),
                        "missing-case",
                        "CUSTOMER",
                        true,
                        null,
                        "U-customer",
                        null,
                        "corr-bad-4"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> rawInsert(
                        fixture.caseId(),
                        "missing-user",
                        "SUPPORT",
                        false,
                        UUID.randomUUID(),
                        null,
                        "QUEUED",
                        "corr-bad-5"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void editDeleteMarkersAndAllDeliveryStatesRemainPersistable() {
        Fixture fixture = fixture();
        UUID supportUserId = createUser();
        Instant providerCreated = Instant.parse("2026-09-23T13:00:00Z");
        Instant edited = Instant.parse("2026-09-23T13:05:00Z");
        Instant deleted = Instant.parse("2026-09-23T13:10:00Z");

        MessageEntity editedCustomer = repository.saveAndFlush(new MessageEntity(
                fixture.caseId(),
                "edited-provider-message",
                "thread-edits",
                MessageKind.CUSTOMER,
                null,
                "U-customer",
                "Customer",
                "treść po edycji",
                MessageBodyFormat.PLAIN_TEXT,
                true,
                null,
                providerCreated,
                edited,
                deleted,
                "corr-edit"));

        assertThat(editedCustomer.editedAt()).isEqualTo(edited);
        assertThat(editedCustomer.deletedAt()).isEqualTo(deleted);

        for (MessageDeliveryStatus status : MessageDeliveryStatus.values()) {
            MessageEntity support = repository.saveAndFlush(new MessageEntity(
                    fixture.caseId(),
                    null,
                    "thread-delivery-" + status.name(),
                    MessageKind.SUPPORT,
                    supportUserId,
                    null,
                    "Agent",
                    "delivery " + status,
                    MessageBodyFormat.PLAIN_TEXT,
                    false,
                    status,
                    null,
                    null,
                    null,
                    "corr-" + status.name().toLowerCase()));
            assertThat(support.deliveryStatus()).isEqualTo(status);
        }
    }

    @Test
    void requiredMessageIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'messages'",
                String.class);

        assertThat(indexes).contains(
                "uq_messages_case_external_message",
                "idx_messages_case_provider_order",
                "idx_messages_case_created_order");
    }

    private static MessageEntity customerMessage(
            UUID caseId,
            String externalMessageId,
            String externalThreadKey,
            String body,
            Instant providerCreatedAt,
            String correlationId) {
        return new MessageEntity(
                caseId,
                externalMessageId,
                externalThreadKey,
                MessageKind.CUSTOMER,
                null,
                "U-customer",
                "Customer",
                body,
                MessageBodyFormat.PLAIN_TEXT,
                true,
                null,
                providerCreatedAt,
                null,
                null,
                correlationId);
    }

    private static void rawInsert(
            UUID caseId,
            String externalMessageId,
            String kind,
            boolean inbound,
            UUID authorUserId,
            String authorExternalId,
            String deliveryStatus,
            String correlationId) {
        jdbc.update("""
                INSERT INTO messages (
                    case_id, external_message_id, external_thread_key, kind,
                    author_user_id, author_external_id, author_name, body, body_format,
                    inbound, delivery_status, provider_created_at, correlation_id
                ) VALUES (?, ?, 'raw-thread', ?, ?, ?, ?, 'body', 'PLAIN_TEXT', ?, ?, CURRENT_TIMESTAMP, ?)
                """,
                caseId,
                externalMessageId,
                kind,
                authorUserId,
                authorExternalId,
                authorExternalId == null ? null : "External Author",
                inbound,
                deliveryStatus,
                correlationId);
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
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
        UUID caseId = jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status
                ) VALUES (?, ?, ?, 'SLACK', ?, ?, 'NEW')
                RETURNING id
                """,
                UUID.class,
                customerId,
                integrationId,
                channelId,
                "conversation-" + suffix,
                "root-" + suffix);
        return new Fixture(caseId);
    }

    private static UUID createUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject(
                "INSERT INTO users (email, display_name, role, active) VALUES (?, ?, 'USER', TRUE) RETURNING id",
                UUID.class,
                "message-owner-" + suffix + "@example.test",
                "Message Owner " + suffix);
    }

    private record Fixture(UUID caseId) {
    }
}
