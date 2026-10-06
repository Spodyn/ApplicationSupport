package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.List;
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
    private static final String SHA256 = "a".repeat(64);

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
    void cleanCaseScopedAttachmentsAreAssociatedWithOutgoingMessage() {
        UUID ownerId = createUser("owner-attachments");
        UUID caseId = createCase(ownerId, "VERIFICATION");
        UUID first = createAttachment(caseId, "CLEAN", 1024);
        UUID second = createAttachment(caseId, "CLEAN", 2048);

        IdempotencyResult result = service.send(
                caseId,
                ownerId,
                "send-with-files",
                "Please see the files",
                MessageBodyFormat.PLAIN_TEXT,
                List.of(first, second),
                "corr-files");

        UUID messageId = UUID.fromString(result.body().get("messageId").asText());
        assertThat(jdbc.queryForList(
                "SELECT message_id FROM attachments WHERE id IN (?, ?) ORDER BY id", UUID.class, first, second))
                .containsOnly(messageId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM attachments WHERE message_id = ?", Integer.class, messageId))
                .isEqualTo(2);
    }

    @Test
    void crossCaseAttachmentIsRejectedWithoutCreatingMessage() {
        UUID ownerId = createUser("owner-cross-case");
        UUID caseId = createCase(ownerId, "VERIFICATION");
        UUID otherCaseId = createCase(ownerId, "VERIFICATION");
        UUID attachmentId = createAttachment(otherCaseId, "CLEAN", 1024);

        assertThatThrownBy(() -> service.send(
                caseId,
                ownerId,
                "cross-case-file",
                "No cross case reuse",
                null,
                List.of(attachmentId),
                "corr-cross-case"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT message_id FROM attachments WHERE id = ?", UUID.class, attachmentId)).isNull();
    }

    @Test
    void nonCleanAttachmentIsRejectedWithoutCreatingMessage() {
        UUID ownerId = createUser("owner-pending-file");
        UUID caseId = createCase(ownerId, "VERIFICATION");
        UUID attachmentId = createAttachment(caseId, "PENDING", 1024);

        assertThatThrownBy(() -> service.send(
                caseId,
                ownerId,
                "pending-file",
                "Still scanning",
                null,
                List.of(attachmentId),
                "corr-pending"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
    }

    @Test
    void attachmentCountAndAggregateSizeLimitsAreEnforced() {
        UUID ownerId = createUser("owner-file-limits");
        UUID caseId = createCase(ownerId, "VERIFICATION");
        List<UUID> eleven = java.util.stream.IntStream.range(0, 11)
                .mapToObj(index -> createAttachment(caseId, "CLEAN", 1))
                .toList();

        assertThatThrownBy(() -> service.send(
                caseId, ownerId, "too-many-files", "Too many", null, eleven, "corr-too-many"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        UUID largeOne = createAttachment(caseId, "CLEAN", 25L * 1024L * 1024L);
        UUID largeTwo = createAttachment(caseId, "CLEAN", 25L * 1024L * 1024L);
        UUID extra = createAttachment(caseId, "CLEAN", 1);
        assertThatThrownBy(() -> service.send(
                caseId,
                ownerId,
                "too-large-total",
                "Too large",
                null,
                List.of(largeOne, largeTwo, extra),
                "corr-too-large"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
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

    @Test
    void askCustomerQueuesTaggedMessageAndKeepsCaseOwnedUntilProviderSuccess() {
        UUID ownerId = createUser("owner-ask");
        UUID caseId = createCase(ownerId, "VERIFICATION");

        IdempotencyResult result = service.ask(
                caseId,
                ownerId,
                "ask-key-1",
                "Could you provide more details?",
                MessageBodyFormat.PLAIN_TEXT,
                null,
                "corr-ask-1");

        assertThat(result.status()).isEqualTo(202);
        UUID messageId = UUID.fromString(result.body().get("messageId").asText());

        assertThat(jdbc.queryForObject(
                "SELECT ask_waiting_seconds FROM messages WHERE id = ?", Long.class, messageId))
                .isEqualTo(24L * 60L * 60L);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?", String.class, caseId))
                .isEqualTo("VERIFICATION");
        assertThat(jdbc.queryForObject(
                "SELECT owner_user_id FROM cases WHERE id = ?", UUID.class, caseId))
                .isEqualTo(ownerId);
        assertThat(jdbc.queryForObject(
                "SELECT waiting_until FROM cases WHERE id = ?", java.time.OffsetDateTime.class, caseId))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'message.send_requested' AND aggregate_id = ?",
                Integer.class,
                messageId)).isEqualTo(1);
    }

    @Test
    void askCustomerValidatesWaitingRangeAndIsIdempotent() {
        UUID ownerId = createUser("owner-ask-range");
        UUID caseId = createCase(ownerId, "VERIFICATION");

        assertThatThrownBy(() -> service.ask(
                caseId, ownerId, "ask-too-short", "Question", null, 59L, "corr-short"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        IdempotencyResult first = service.ask(
                caseId, ownerId, "ask-repeat", "Question", null, 60L, "corr-first");
        IdempotencyResult replay = service.ask(
                caseId, ownerId, "ask-repeat", "Question", null, 60L, "corr-replay");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isEqualTo(1);
        UUID messageId = UUID.fromString(first.body().get("messageId").asText());
        assertThat(jdbc.queryForObject(
                "SELECT ask_waiting_seconds FROM messages WHERE id = ?", Long.class, messageId))
                .isEqualTo(60L * 60L);
    }

    private UUID createAttachment(UUID caseId, String scanStatus, long sizeBytes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO attachments (
                    id, case_id, message_id, storage_key, original_filename, content_type,
                    detected_content_type, size_bytes, sha256, scan_status, scan_error, provider_file_id
                ) VALUES (?, ?, NULL, ?, 'file.txt', 'text/plain', 'text/plain', ?, ?, ?, NULL, NULL)
                """,
                id,
                caseId,
                "attachments/aa/" + id,
                sizeBytes,
                SHA256,
                scanStatus);
        return id;
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
