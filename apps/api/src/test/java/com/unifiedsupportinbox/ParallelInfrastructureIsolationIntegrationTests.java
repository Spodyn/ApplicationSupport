package com.unifiedsupportinbox;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.unifiedsupportinbox.testing.TestInfrastructure;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class ParallelInfrastructureIsolationIntegrationTests {

    @Test
    @Timeout(value = 2, unit = java.util.concurrent.TimeUnit.MINUTES)
    void suiteOwnedPostgresInstancesRemainIsolatedWhenUsedInParallel() throws Exception {
        CyclicBarrier bothSuitesRunning = new CyclicBarrier(2);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<SuiteResult> first = executor.submit(() -> exerciseSuite("first", bothSuitesRunning));
            Future<SuiteResult> second = executor.submit(() -> exerciseSuite("second", bothSuitesRunning));

            SuiteResult firstResult = first.get();
            SuiteResult secondResult = second.get();

            assertThat(firstResult.jdbcUrl()).isNotEqualTo(secondResult.jdbcUrl());
            assertThat(firstResult.mappedPort()).isNotEqualTo(secondResult.mappedPort());
            assertThat(firstResult.marker()).isEqualTo("first");
            assertThat(secondResult.marker()).isEqualTo("second");
        }
    }

    private SuiteResult exerciseSuite(String marker, CyclicBarrier bothSuitesRunning) throws Exception {
        PostgreSQLContainer postgres = TestInfrastructure.postgres();
        postgres.start();

        try {
            bothSuitesRunning.await(30, SECONDS);

            try (Connection connection = connection(postgres);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE parallel_suite_probe (marker varchar(32) primary key)");
            }

            try (Connection connection = connection(postgres);
                 var statement = connection.prepareStatement(
                         "INSERT INTO parallel_suite_probe (marker) VALUES (?)")) {
                statement.setString(1, marker);
                statement.executeUpdate();
            }

            assertThat(queryString(postgres, "SELECT marker FROM parallel_suite_probe")).isEqualTo(marker);

            TestInfrastructure.resetPostgres(postgres);

            assertThat(queryBoolean(
                    postgres,
                    "SELECT to_regclass('public.parallel_suite_probe') IS NULL"))
                    .isTrue();
            assertThat(queryBoolean(
                    postgres,
                    "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL"))
                    .isTrue();

            return new SuiteResult(postgres.getJdbcUrl(), postgres.getMappedPort(5432), marker);
        } finally {
            postgres.stop();
        }
    }

    private Connection connection(PostgreSQLContainer postgres) throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private boolean queryBoolean(PostgreSQLContainer postgres, String sql) throws Exception {
        try (Connection connection = connection(postgres);
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private String queryString(PostgreSQLContainer postgres, String sql) throws Exception {
        try (Connection connection = connection(postgres);
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private record SuiteResult(String jdbcUrl, int mappedPort, String marker) {
    }
}
