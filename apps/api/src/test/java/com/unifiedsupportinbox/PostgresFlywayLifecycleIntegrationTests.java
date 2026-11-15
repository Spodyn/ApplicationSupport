package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;

import com.unifiedsupportinbox.testing.TestInfrastructure;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class PostgresFlywayLifecycleIntegrationTests {

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
    void cleanDatabaseMigratesBeforeContextStartAndSecondStartIsIdempotent() throws Exception {
        cleanDatabase();
        assertThat(tableExists("flyway_schema_history")).isFalse();

        int historyRowsAfterFirstStart;
        try (ConfigurableApplicationContext first = startApplication()) {
            Flyway flyway = first.getBean(Flyway.class);

            flyway.validate();
            assertThat(first.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                    .isEqualTo("validate");
            assertThat(queryString("SHOW server_version")).startsWith("18.");
            assertThat(tableExists("flyway_schema_history")).isTrue();
            assertThat(queryInt("SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE success AND version = '1'"))
                    .isEqualTo(1);
            historyRowsAfterFirstStart = queryInt(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success");
            assertThat(historyRowsAfterFirstStart).isPositive();
            assertThat(flyway.info().pending()).isEmpty();
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            Flyway flyway = second.getBean(Flyway.class);
            flyway.validate();
            assertThat(flyway.info().pending()).isEmpty();
        }

        assertThat(queryInt("SELECT COUNT(*) FROM flyway_schema_history WHERE success"))
                .isEqualTo(historyRowsAfterFirstStart);
    }

    @Test
    void upgradesAPreviousCommittedMigrationSnapshotWithoutChecksumOrPendingMigrations()
            throws Exception {
        cleanDatabase();
        Path snapshot = Files.createTempDirectory("usi-flyway-v1-snapshot-");
        try {
            Path baseline = Path.of(Objects.requireNonNull(
                    PostgresFlywayLifecycleIntegrationTests.class
                            .getClassLoader()
                            .getResource("db/migration/V1__baseline.sql"))
                    .toURI());
            Files.copy(baseline, snapshot.resolve("V1__baseline.sql"));

            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("filesystem:" + snapshot.toAbsolutePath())
                    .load()
                    .migrate();
            assertThat(queryInt("SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE success AND version = '1'"))
                    .isEqualTo(1);

            Flyway current = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load();
            current.migrate();
            current.validate();

            assertThat(current.info().pending()).isEmpty();
            assertThat(queryInt("SELECT COUNT(*) FROM flyway_schema_history WHERE success"))
                    .isGreaterThan(1);
            assertThat(tableExists("inbound_events")).isTrue();
        } finally {
            deleteRecursively(snapshot);
        }
    }

    @Test
    void upgradesCaseAttachmentDeliveryFromV42() throws SQLException {
        cleanDatabase();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("42").load().migrate();
        assertThat(tableExists("slack_outbound_uploads")).isFalse();
        Flyway current = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("43").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        current.validate();
        assertThat(tableExists("slack_outbound_uploads")).isTrue();
    }

    @Test
    void upgradesAttachmentQueueFromV43() throws SQLException {
        cleanDatabase();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("43").load().migrate();
        Flyway current = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("44").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        current.validate();
        assertThat(queryInt("SELECT count(*) FROM information_schema.columns WHERE table_name = 'attachments'"
                + " AND column_name = 'slack_ingest_pending'")).isEqualTo(1);
    }

    @Test
    void upgradesFromV44WithPrivateSnoozeHistory() throws SQLException {
        cleanDatabase();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("44").load().migrate();
        assertThat(tableExists("case_snooze_history")).isFalse();
        Flyway current = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("45").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        current.validate();
        assertThat(tableExists("case_snooze_history")).isTrue();
    }

    @Test
    void upgradesFromV45ToInternalNotesAndRetainsExistingUsers() throws SQLException {
        cleanDatabase();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("45").load().migrate();
        assertThat(tableExists("case_internal_notes")).isFalse();
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (email, display_name, role, active)
                    VALUES ('note-upgrade@example.test', 'Existing user', 'USER', TRUE)
                    """);
        }

        Flyway current = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("46").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        current.validate();
        assertThat(tableExists("case_internal_notes")).isTrue();
        assertThat(queryBoolean("SELECT to_regclass('public.idx_case_internal_notes_history') IS NOT NULL")).isTrue();
        assertThat(queryString("SELECT display_name FROM users WHERE email = 'note-upgrade@example.test'"))
                .isEqualTo("Existing user");
    }

    @Test
    void addsCanonicalWorkflowProjectionToV46() throws SQLException {
        cleanDatabase();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("46").load().migrate();
        Flyway current = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("47").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        current.validate();
        assertThat(queryString("""
                SELECT is_generated FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'cases' AND column_name = 'workflow_status'
                """)).isEqualTo("ALWAYS");
        assertThat(tableExists("case_internal_notes")).isTrue();
    }

    private static ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(UsiApiApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=validate");
    }

    private static void cleanDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    private static boolean tableExists(String tableName) throws SQLException {
        return queryBoolean("SELECT to_regclass('public." + tableName + "') IS NOT NULL");
    }

    private static boolean queryBoolean(String sql) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static int queryInt(String sql) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void deleteRecursively(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }
}
