package com.unifiedsupportinbox.cases.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class CaseReferenceGenerationIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void upgradeStartsSequenceAboveHighestExistingReference() throws Exception {
        clean();
        migrateTo17();
        Fixture fixture = fixture();
        insertExplicitCase(fixture, "CASE-00000420", "legacy-conversation", "legacy-thread");

        migrateCurrent();

        String generated = insertGeneratedCase(fixture, "new-conversation", "new-thread");
        assertThat(generated).isEqualTo("CASE-00000421");
    }

    @Test
    void concurrentCaseCreationGeneratesUniqueCanonicalReferences() throws Exception {
        clean();
        migrateCurrent();
        Fixture fixture = fixture();
        int contenders = 24;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();

        try {
            for (int index = 0; index < contenders; index++) {
                int candidate = index;
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Reference contenders did not start together.");
                    }
                    return insertGeneratedCase(
                            fixture,
                            "reference-race-" + candidate,
                            "reference-thread-" + candidate);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<String> references = new HashSet<>();
            for (Future<String> attempt : attempts) {
                references.add(attempt.get(20, TimeUnit.SECONDS));
            }

            assertThat(references).hasSize(contenders);
            assertThat(references).allMatch(reference -> reference.matches("CASE-[0-9]{8,}"));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void clean() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    private static void migrateTo17() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("17")
                .load()
                .migrate();
    }

    private static void migrateCurrent() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().pending()).isEmpty();
    }

    private static void insertExplicitCase(
            Fixture fixture, String reference, String conversation, String thread) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO cases (
                         reference, customer_id, integration_id, channel_id, provider,
                         external_conversation_id, external_thread_key, status
                     ) VALUES (?, ?, ?, ?, 'SLACK', ?, ?, 'NEW')
                     """)) {
            statement.setString(1, reference);
            statement.setObject(2, fixture.customerId());
            statement.setObject(3, fixture.integrationId());
            statement.setObject(4, fixture.channelId());
            statement.setString(5, conversation);
            statement.setString(6, thread);
            statement.executeUpdate();
        }
    }

    private static String insertGeneratedCase(
            Fixture fixture, String conversation, String thread) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO cases (
                         customer_id, integration_id, channel_id, provider,
                         external_conversation_id, external_thread_key, status
                     ) VALUES (?, ?, ?, 'SLACK', ?, ?, 'NEW')
                     RETURNING reference
                     """)) {
            statement.setObject(1, fixture.customerId());
            statement.setObject(2, fixture.integrationId());
            statement.setObject(3, fixture.channelId());
            statement.setString(4, conversation);
            statement.setString(5, thread);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static Fixture fixture() throws SQLException {
        String suffix = UUID.randomUUID().toString();
        UUID customerId;
        UUID integrationId;
        UUID channelId;
        try (Connection connection = connection()) {
            customerId = returningUuid(connection, """
                    INSERT INTO customers (name, external_ref)
                    VALUES (?, ?)
                    RETURNING id
                    """, "Customer " + suffix, "customer-" + suffix);
            integrationId = returningUuid(connection, """
                    INSERT INTO integrations (
                        provider, display_name, status, health, workspace_external_id
                    ) VALUES ('SLACK', ?, 'ENABLED', 'HEALTHY', ?)
                    RETURNING id
                    """, "Slack " + suffix, "workspace-" + suffix);
            channelId = returningUuid(connection, """
                    INSERT INTO channels (
                        integration_id, external_channel_id, name, customer_id,
                        ignored, grouping_strategy, active
                    ) VALUES (?, ?, ?, ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                    RETURNING id
                    """, integrationId, "channel-" + suffix, "support-" + suffix, customerId);
        }
        return new Fixture(customerId, integrationId, channelId);
    }

    private static UUID returningUuid(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record Fixture(UUID customerId, UUID integrationId, UUID channelId) {
    }
}
