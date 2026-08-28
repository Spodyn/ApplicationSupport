package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.unifiedsupportinbox.testing.TestInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class PostgresConcurrencyConventionsIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetProbeTables() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS due_work_probe");
            statement.execute("DROP TABLE IF EXISTS claim_probe");
            statement.execute("""
                    CREATE TABLE claim_probe (
                        id bigint PRIMARY KEY,
                        status text NOT NULL,
                        owner text
                    )
                    """);
            statement.execute("""
                    INSERT INTO claim_probe(id, status, owner)
                    VALUES (1, 'NEW', NULL)
                    """);
            statement.execute("""
                    CREATE TABLE due_work_probe (
                        id bigint PRIMARY KEY,
                        due_at timestamptz NOT NULL,
                        processed_by text
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_due_work_probe_ready
                    ON due_work_probe(due_at, id)
                    WHERE processed_by IS NULL
                    """);
            statement.execute("""
                    INSERT INTO due_work_probe(id, due_at, processed_by)
                    VALUES
                        (1, CURRENT_TIMESTAMP - INTERVAL '2 minutes', NULL),
                        (2, CURRENT_TIMESTAMP - INTERVAL '1 minute', NULL)
                    """);
        }
    }

    @Test
    void postgresUsesReadCommittedAsTheDefaultIsolation() throws SQLException {
        assertThat(queryString("SHOW transaction_isolation")).isEqualTo("read committed");
    }

    @Test
    void conditionalUpdateAllowsExactlyOneClaimWinner() throws Exception {
        int contenders = 20;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> attempts = new ArrayList<>();

        try {
            for (int i = 0; i < contenders; i++) {
                String worker = "worker-" + i;
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("claim contenders did not start together");
                    }

                    try (Connection connection = connection();
                         PreparedStatement statement = connection.prepareStatement("""
                                 UPDATE claim_probe
                                 SET owner = ?
                                 WHERE id = 1
                                   AND owner IS NULL
                                   AND status = 'NEW'
                                 """)) {
                        statement.setString(1, worker);
                        return statement.executeUpdate();
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> results = new ArrayList<>();
            for (Future<Integer> attempt : attempts) {
                results.add(attempt.get(20, TimeUnit.SECONDS));
            }

            assertThat(results).containsOnly(0, 1);
            assertThat(results.stream().filter(result -> result == 1).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result == 0).count())
                    .isEqualTo(contenders - 1L);
            assertThat(queryString("SELECT owner FROM claim_probe WHERE id = 1"))
                    .startsWith("worker-");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void skipLockedLetsTwoWorkersClaimDifferentDueRowsWithoutWaiting() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier bothRowsLocked = new CyclicBarrier(2);

        try {
            Future<ClaimedWork> first = executor.submit(
                    () -> claimOneDueRow("worker-a", ready, start, bothRowsLocked));
            Future<ClaimedWork> second = executor.submit(
                    () -> claimOneDueRow("worker-b", ready, start, bothRowsLocked));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ClaimedWork firstClaim = first.get(20, TimeUnit.SECONDS);
            ClaimedWork secondClaim = second.get(20, TimeUnit.SECONDS);

            Set<Long> claimedIds = new HashSet<>(List.of(firstClaim.id(), secondClaim.id()));
            assertThat(claimedIds).containsExactlyInAnyOrder(1L, 2L);
            assertThat(firstClaim.worker()).isNotEqualTo(secondClaim.worker());
            assertThat(queryInt("SELECT COUNT(*) FROM due_work_probe WHERE processed_by IS NOT NULL"))
                    .isEqualTo(2);
            assertThat(queryInt("SELECT COUNT(DISTINCT processed_by) FROM due_work_probe"))
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static ClaimedWork claimOneDueRow(
            String worker,
            CountDownLatch ready,
            CountDownLatch start,
            CyclicBarrier bothRowsLocked) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("due workers did not start together");
        }

        try (Connection connection = connection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);

            try {
                long id;
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT id
                        FROM due_work_probe
                        WHERE processed_by IS NULL
                          AND due_at <= CURRENT_TIMESTAMP
                        ORDER BY due_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                        """);
                     ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("expected a due row to claim");
                    }
                    id = result.getLong(1);
                }

                bothRowsLocked.await(10, TimeUnit.SECONDS);

                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE due_work_probe
                        SET processed_by = ?
                        WHERE id = ?
                        """)) {
                    update.setString(1, worker);
                    update.setLong(2, id);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }

                connection.commit();
                return new ClaimedWork(id, worker);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static int queryInt(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record ClaimedWork(long id, String worker) {
    }
}
