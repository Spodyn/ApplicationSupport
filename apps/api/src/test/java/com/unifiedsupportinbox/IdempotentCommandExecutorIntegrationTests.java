package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class IdempotentCommandExecutorIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static IdempotentCommandExecutor commandExecutor;
    private static IdempotencyCleanup cleanup;
    private static JdbcTemplate jdbc;
    private static ObjectMapper objectMapper;

    private UUID userId;

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

        commandExecutor = context.getBean(IdempotentCommandExecutor.class);
        cleanup = context.getBean(IdempotencyCleanup.class);
        jdbc = context.getBean(JdbcTemplate.class);
        objectMapper = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) {
            context.close();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update(
                "UPDATE bootstrap_admin_state "
                        + "SET consumed = FALSE, consumed_at = NULL, admin_user_id = NULL "
                        + "WHERE id = 1");
        jdbc.update("DELETE FROM users");
        userId = createUser();
    }

    @Test
    void sameKeyAndCanonicalRequestReplayTheSameResponseWithoutRepeatingEffect() {
        AtomicInteger effects = new AtomicInteger();
        Map<String, Object> firstRequest = new LinkedHashMap<>();
        firstRequest.put("caseId", "case-1");
        firstRequest.put("reason", "retry");
        Map<String, Object> reorderedRequest = new LinkedHashMap<>();
        reorderedRequest.put("reason", "retry");
        reorderedRequest.put("caseId", "case-1");

        IdempotencyResult first = commandExecutor.execute(
                userId,
                "case.resolve",
                "retry-key-1",
                firstRequest,
                () -> response(effects.incrementAndGet()));
        IdempotencyResult replay = commandExecutor.execute(
                userId,
                "case.resolve",
                "retry-key-1",
                reorderedRequest,
                () -> response(effects.incrementAndGet()));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(effects).hasValue(1);
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentRequestReturnsConflictWithoutExecutingAgain() {
        AtomicInteger effects = new AtomicInteger();
        commandExecutor.execute(
                userId,
                "case.claim",
                "claim-key",
                Map.of("caseId", "case-1"),
                () -> response(effects.incrementAndGet()));

        assertThatThrownBy(() -> commandExecutor.execute(
                userId,
                "case.claim",
                "claim-key",
                Map.of("caseId", "case-2"),
                () -> response(effects.incrementAndGet())))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(problem.code()).isEqualTo(ApiProblemCode.CONFLICT);
                });
        assertThat(effects).hasValue(1);
    }

    @Test
    void concurrentFirstRequestsExecuteBusinessEffectExactlyOnce() throws Exception {
        int contenders = 8;
        AtomicInteger effects = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<IdempotencyResult>> futures = new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency start latch timed out");
                    }
                    return commandExecutor.execute(
                            userId,
                            "case.claim",
                            "concurrent-key",
                            Map.of("caseId", "case-1"),
                            () -> response(effects.incrementAndGet()));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<IdempotencyResult> results = new ArrayList<>();
            for (Future<IdempotencyResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            assertThat(effects).hasValue(1);
            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            assertThat(results).filteredOn(IdempotencyResult::replayed).hasSize(contenders - 1);
            assertThat(results).extracting(IdempotencyResult::body).containsOnly(results.getFirst().body());
            assertThat(countRows()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredKeyCanBeReusedAndCleanupRemovesExpiredRows() {
        AtomicInteger effects = new AtomicInteger();
        commandExecutor.execute(
                userId,
                "case.resolve",
                "expiring-key",
                Map.of("caseId", "case-1"),
                () -> response(effects.incrementAndGet()));
        jdbc.update("UPDATE idempotency_keys SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");

        IdempotencyResult afterExpiry = commandExecutor.execute(
                userId,
                "case.resolve",
                "expiring-key",
                Map.of("caseId", "case-2"),
                () -> response(effects.incrementAndGet()));

        assertThat(afterExpiry.replayed()).isFalse();
        assertThat(effects).hasValue(2);
        assertThat(countRows()).isEqualTo(1);

        jdbc.update("UPDATE idempotency_keys SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertThat(cleanup.cleanupExpiredNow()).isEqualTo(1);
        assertThat(countRows()).isZero();
    }

    @Test
    void failedBusinessTransactionRollsBackEffectAndKeySoRetryCanExecute() {
        String key = "rollback-key";
        assertThatThrownBy(() -> commandExecutor.execute(
                userId,
                "user.rename",
                key,
                Map.of("displayName", "Rolled Back"),
                () -> {
                    jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", "Rolled Back", userId);
                    throw new IllegalStateException("simulated command failure");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(displayName()).isEqualTo("Idempotency Test User");
        assertThat(countRows()).isZero();

        IdempotencyResult retry = commandExecutor.execute(
                userId,
                "user.rename",
                key,
                Map.of("displayName", "Committed"),
                () -> {
                    jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", "Committed", userId);
                    return response(1);
                });

        assertThat(retry.replayed()).isFalse();
        assertThat(displayName()).isEqualTo("Committed");
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void rejectsOversizedOrControlCharacterKeysBeforeDatabaseMutation() {
        String oversized = "x".repeat(ApiV1Conventions.MAX_IDEMPOTENCY_KEY_LENGTH + 1);
        assertThatThrownBy(() -> commandExecutor.execute(
                userId,
                "case.claim",
                oversized,
                Map.of(),
                () -> response(1)))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo(ApiProblemCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> commandExecutor.execute(
                userId,
                "case.claim",
                "bad\nkey",
                Map.of(),
                () -> response(1)))
                .isInstanceOf(ApiProblemException.class);
        assertThat(countRows()).isZero();
    }

    private IdempotencyResponse response(int sequence) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("result", "ok");
        body.put("sequence", sequence);
        return new IdempotencyResponse(200, body);
    }

    private UUID createUser() {
        return jdbc.queryForObject(
                """
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, ?, 'USER', TRUE)
                RETURNING id
                """,
                UUID.class,
                "idempotency-" + UUID.randomUUID() + "@example.com",
                "Idempotency Test User");
    }

    private int countRows() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class);
        return count == null ? 0 : count;
    }

    private String displayName() {
        return jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, userId);
    }
}
