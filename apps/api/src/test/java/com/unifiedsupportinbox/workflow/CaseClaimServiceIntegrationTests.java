package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemCode;
import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class CaseClaimServiceIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseClaimService claims;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class).profiles("test").run(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.session.jdbc.initialize-schema=never", "--usi.bootstrap-admin.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        claims = context.getBean(CaseClaimService.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void claimIsIdempotentAndAtomicallyClearsActiveVoteAndSnoozeState() {
        UUID caseId = newCase();
        UUID claimant = newUser();
        UUID voter = newUser();
        jdbc.update("INSERT INTO case_ignore_votes (case_id, user_id, weight) VALUES (?, ?, 1)", caseId, voter);
        jdbc.update("INSERT INTO case_snoozes (case_id, user_id, until_at) VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                caseId, voter);

        var first = claims.claim(caseId, claimant, "claim-idempotency", "claim-correlation");
        var replay = claims.claim(caseId, claimant, "claim-idempotency", "claim-correlation");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().get("caseId").asString()).isEqualTo(first.body().get("caseId").asString());
        assertThat(replay.body().get("status").asString()).isEqualTo("VERIFICATION");
        assertThat(replay.body().get("ownerUserId").asString()).isEqualTo(claimant.toString());
        assertThat(replay.body().get("version").asLong()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM cases WHERE id = ?", String.class, caseId)).isEqualTo("VERIFICATION");
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM cases WHERE id = ?", UUID.class, caseId)).isEqualTo(claimant);
        assertThat(jdbc.queryForObject("SELECT active FROM case_ignore_votes WHERE case_id = ?", Boolean.class, caseId)).isFalse();
        assertThat(count("case_snoozes", caseId)).isZero();
        assertThat(count("outbox_events", caseId)).isEqualTo(1);
        assertThat(count("audit_events", caseId)).isEqualTo(1);
    }

    @Test
    void formerIgnoreVoterCannotClaimAndConcurrentClaimsHaveOneWinner() throws Exception {
        UUID restrictedCase = newCase();
        UUID formerVoter = newUser();
        jdbc.update("INSERT INTO case_ignore_votes (case_id, user_id, weight, active, deactivated_at) "
                        + "VALUES (?, ?, 1, FALSE, CURRENT_TIMESTAMP)", restrictedCase, formerVoter);
        assertThatThrownBy(() -> claims.claim(restrictedCase, formerVoter, "restricted", "claim-correlation"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo(ApiProblemCode.CONFLICT));

        UUID caseId = newCase();
        List<UUID> users = new ArrayList<>();
        for (int index = 0; index < 20; index++) users.add(newUser());
        CountDownLatch ready = new CountDownLatch(users.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(users.size())) {
            var futures = users.stream().map(user -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("claim barrier timed out");
                try { claims.claim(caseId, user, "concurrent-" + user, "claim-correlation"); return true; }
                catch (ApiProblemException error) {
                    assertThat(error.code()).isEqualTo(ApiProblemCode.CASE_ALREADY_CLAIMED);
                    return false;
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int winners = 0;
            for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
    }

    private static int count(String table, UUID caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE "
                + (table.equals("outbox_events") ? "aggregate_id" : "case_id") + " = ?", Integer.class, caseId);
    }

    private static UUID newUser() {
        return jdbc.queryForObject("INSERT INTO users (email, display_name, role, active) VALUES (?, 'Agent', 'USER', TRUE) RETURNING id",
                UUID.class, UUID.randomUUID() + "@example.test");
    }

    private static UUID newCase() {
        String key = UUID.randomUUID().toString();
        UUID customer = jdbc.queryForObject("INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class, "Customer " + key, key);
        UUID integration = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', 'Slack', 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, key);
        UUID channel = jdbc.queryForObject("""
                INSERT INTO channels (integration_id, external_channel_id, name, customer_id, ignored, grouping_strategy, active)
                VALUES (?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE) RETURNING id
                """, UUID.class, integration, key, customer);
        return jdbc.queryForObject("""
                INSERT INTO cases (customer_id, integration_id, channel_id, provider, external_conversation_id)
                VALUES (?, ?, ?, 'SLACK', ?) RETURNING id
                """, UUID.class, customer, integration, channel, key);
    }
}
