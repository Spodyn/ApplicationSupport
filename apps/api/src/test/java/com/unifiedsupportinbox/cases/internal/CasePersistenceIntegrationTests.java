package com.unifiedsupportinbox.cases.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.cases.CaseStatus;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class CasePersistenceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseRepository repository;

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
        repository = context.getBean(CaseRepository.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void clearCases() {
        jdbc.update("DELETE FROM cases");
    }

    @Test
    void flywayAndJpaPersistCanonicalNewCaseAndLookupActiveProviderContext() {
        Fixture fixture = fixture();
        CaseEntity entity = new CaseEntity(
                "CASE-00000001",
                fixture.customerId(),
                fixture.integrationId(),
                fixture.channelId(),
                IntegrationProvider.SLACK,
                "C-support",
                "1710000000.000001",
                null);

        CaseEntity saved = repository.saveAndFlush(entity);

        assertThat(saved.id()).isNotNull();
        assertThat(saved.id().version()).isEqualTo(7);
        assertThat(saved.status()).isEqualTo(CaseStatus.NEW);
        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(saved.lastActivityAt()).isNotNull();
        assertThat(saved.version()).isZero();
        assertThat(repository.findByReference("CASE-00000001")).contains(saved);
        assertThat(repository.findActiveByProviderContext(
                        fixture.integrationId(),
                        fixture.channelId(),
                        IntegrationProvider.SLACK,
                        "C-support",
                        "1710000000.000001"))
                .contains(saved);
    }

    @Test
    void databaseEnforcesOwnerAndStateTimestampInvariants() {
        Fixture fixture = fixture();
        UUID ownerId = createUser();
        Instant now = Instant.now();

        assertThatThrownBy(() -> insertCase(
                        fixture, "CASE-00000002", "new-with-owner", "root-a", "NEW",
                        ownerId, now, null, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertCase(
                        fixture, "CASE-00000003", "verification-without-owner", "root-b", "VERIFICATION",
                        null, null, null, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertCase(
                        fixture, "CASE-00000004", "waiting-without-deadline", "root-c", "WAITING_FOR_CUSTOMER",
                        null, now, null, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertCase(
                        fixture, "CASE-00000005", "resolved-without-timestamp", "root-d", "RESOLVED",
                        ownerId, now, null, null, null, "SOLVED", null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertCase(
                        fixture, "CASE-00000006", "ignored-without-timestamp", "root-e", "IGNORED",
                        null, null, null, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID verification = insertCase(
                fixture, "CASE-00000007", "valid-verification", "root-f", "VERIFICATION",
                ownerId, now, null, null, null, null, null);
        assertThat(verification).isNotNull();
    }

    @Test
    void terminalCaseAllowsOneLinkedSuccessorButTwoActiveCasesForSameContextAreRejected() {
        Fixture fixture = fixture();
        String conversation = "C-terminal-link";
        String thread = "1710000000.000777";

        UUID first = insertCase(
                fixture, "CASE-00000008", conversation, thread, "NEW",
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> insertCase(
                        fixture, "CASE-00000009", conversation, thread, "NEW",
                        null, null, null, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update(
                "UPDATE cases SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE id = ?",
                first);

        UUID successor = insertCase(
                fixture, "CASE-00000010", conversation, thread, "NEW",
                null, null, null, null, null, null, first);

        assertThat(successor).isNotNull().isNotEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT related_case_id FROM cases WHERE id = ?", UUID.class, successor)).isEqualTo(first);
    }

    @Test
    void nullThreadContextsAreAlsoProtectedByActiveCaseUniqueness() {
        Fixture fixture = fixture();

        insertCase(
                fixture, "CASE-00000011", "group-chat-42", null, "NEW",
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> insertCase(
                        fixture, "CASE-00000012", "group-chat-42", null, "NEW",
                        null, null, null, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void referenceAndRelatedCaseConstraintsAreEnforced() {
        Fixture fixture = fixture();

        insertCase(
                fixture, "CASE-00000013", "reference-a", "thread-a", "NEW",
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> insertCase(
                        fixture, "CASE-00000013", "reference-b", "thread-b", "NEW",
                        null, null, null, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertCase(
                        fixture, "bad-reference", "reference-c", "thread-c", "NEW",
                        null, null, null, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID self = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO cases (
                            id, reference, customer_id, integration_id, channel_id, provider,
                            external_conversation_id, external_thread_key, status, related_case_id
                        ) VALUES (?, 'CASE-00000014', ?, ?, ?, 'SLACK', 'self-related', 'thread-self', 'NEW', ?)
                        """,
                        self,
                        fixture.customerId(),
                        fixture.integrationId(),
                        fixture.channelId(),
                        self))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiredCaseIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'cases'",
                String.class);

        assertThat(indexes).contains(
                "idx_cases_status_activity",
                "idx_cases_owner_status",
                "idx_cases_channel_thread",
                "idx_cases_customer_activity",
                "uq_cases_active_provider_context");
    }

    @Test
    void concurrentFirstMessagesProduceExactlyOneActiveCaseForProviderContext() throws Exception {
        Fixture fixture = fixture();
        int contenders = 12;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        try {
            for (int index = 0; index < contenders; index++) {
                int candidate = index;
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Case contenders did not start together.");
                    }
                    return tryInsertConcurrentCase(fixture, candidate);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(20, TimeUnit.SECONDS)) successes++;
            }

            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*)
                    FROM cases
                    WHERE integration_id = ?
                      AND channel_id = ?
                      AND provider = 'SLACK'
                      AND external_conversation_id = 'race-channel'
                      AND external_thread_key = 'race-root'
                      AND status NOT IN ('IGNORED', 'RESOLVED')
                    """, Integer.class, fixture.integrationId(), fixture.channelId())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static boolean tryInsertConcurrentCase(Fixture fixture, int candidate) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO cases (
                         reference, customer_id, integration_id, channel_id, provider,
                         external_conversation_id, external_thread_key, status
                     ) VALUES (?, ?, ?, ?, 'SLACK', 'race-channel', 'race-root', 'NEW')
                     """)) {
            statement.setString(1, "CASE-" + String.format("%08d", 1000 + candidate));
            statement.setObject(2, fixture.customerId());
            statement.setObject(3, fixture.integrationId());
            statement.setObject(4, fixture.channelId());
            statement.executeUpdate();
            return true;
        } catch (SQLException duplicate) {
            if ("23505".equals(duplicate.getSQLState())) return false;
            throw duplicate;
        }
    }

    private static UUID insertCase(
            Fixture fixture,
            String reference,
            String conversation,
            String thread,
            String status,
            UUID ownerId,
            Instant claimedAt,
            Instant waitingUntil,
            Instant resolvedAt,
            Instant ignoredAt,
            String resolutionCategory,
            UUID relatedCaseId) {
        return jdbc.queryForObject("""
                INSERT INTO cases (
                    reference, customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status,
                    owner_user_id, claimed_at, waiting_until, resolved_at, ignored_at,
                    resolution_category, related_case_id
                ) VALUES (?, ?, ?, ?, 'SLACK', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                UUID.class,
                reference,
                fixture.customerId(),
                fixture.integrationId(),
                fixture.channelId(),
                conversation,
                thread,
                status,
                ownerId,
                utc(claimedAt),
                utc(waitingUntil),
                utc(resolvedAt),
                utc(ignoredAt),
                resolutionCategory,
                relatedCaseId);
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
        return new Fixture(customerId, integrationId, channelId);
    }

    private static UUID createUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject(
                "INSERT INTO users (email, display_name, role, active) VALUES (?, ?, 'USER', TRUE) RETURNING id",
                UUID.class,
                "case-owner-" + suffix + "@example.test",
                "Case Owner " + suffix);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Fixture(UUID customerId, UUID integrationId, UUID channelId) {
    }
}
