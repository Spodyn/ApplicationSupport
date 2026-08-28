package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

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
}
