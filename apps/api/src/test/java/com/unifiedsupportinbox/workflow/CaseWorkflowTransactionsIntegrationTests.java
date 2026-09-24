package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.*;

import com.unifiedsupportinbox.ApiProblemCode;
import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.cases.CaseStatus;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import com.unifiedsupportinbox.workflow.CaseTransitionPolicy.*;
import java.util.Set;
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
class CaseWorkflowTransactionsIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseWorkflowTransactions workflow;

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
        workflow = context.getBean(CaseWorkflowTransactions.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void persistsPolicyDecisionAndReturnsAuthoritativeAvailability() {
        UUID id = newCase();
        Actor actor = newActor();
        var claimed = execute(id, 0, actor, Action.CLAIM);
        assertThat(claimed.state().status()).isEqualTo(CaseStatus.VERIFICATION);
        assertThat(claimed.state().ownerId()).isEqualTo(actor.id());
        assertThat(claimed.version()).isEqualTo(1);
        assertThat(claimed.availableActions()).contains(Action.REPLY, Action.RESOLVE).doesNotContain(Action.CLAIM);
        assertThat(jdbc.queryForObject("SELECT claimed_at IS NOT NULL FROM cases WHERE id = ?", Boolean.class, id)).isTrue();
        var resolved = execute(id, 1, actor, Action.RESOLVE);
        assertThat(resolved.state().status()).isEqualTo(CaseStatus.RESOLVED);
        assertThat(resolved.state().ownerId()).isEqualTo(actor.id());
        assertThat(resolved.version()).isEqualTo(2);
        assertThat(resolved.availableActions()).containsExactly(Action.MARK_READ);
        assertThat(jdbc.queryForObject("SELECT resolved_at IS NOT NULL FROM cases WHERE id = ?", Boolean.class, id)).isTrue();
        assertThatThrownBy(() -> execute(id, 2, actor, Action.CLAIM)).isInstanceOf(ApiProblemException.class);
    }

    @Test
    void ignoreResultImmediatelyReflectsLifetimeClaimRestriction() {
        var result = execute(newCase(), 0, newActor(), Action.IGNORE);
        assertThat(result.state().status()).isEqualTo(CaseStatus.PARTIALLY_IGNORED);
        assertThat(result.availableActions()).doesNotContain(Action.CLAIM, Action.REPLY);
    }

    @Test
    void effectFailureRollsBackWorkflowAndOtherWrites() {
        UUID id = newCase();
        Actor actor = newActor();
        assertThatThrownBy(() -> workflow.execute(id, 0,
                state -> new CaseWorkflowTransactions.Command(actor, Action.CLAIM, Input.empty()),
                decision -> {
                    jdbc.update("UPDATE users SET display_name = 'rolled-back' WHERE id = ?", actor.id());
                    throw new IllegalStateException("Simulated outbox/audit failure");
                })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM cases WHERE id = ?", String.class, id)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT version FROM cases WHERE id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, actor.id())).isEqualTo("Agent");
    }

    @Test
    void staleVersionAndMissingCaseDoNotInvokeCommandOrEffects() {
        UUID id = newCase();
        execute(id, 0, newActor(), Action.CLAIM);
        for (UUID candidate : new UUID[] {id, UUID.randomUUID()}) {
            assertThatThrownBy(() -> workflow.execute(candidate, 0,
                    state -> { throw new AssertionError("Must not load stale command"); },
                    decision -> { throw new AssertionError("Must not write effects"); }))
                    .isInstanceOfSatisfying(ApiProblemException.class, ex -> assertThat(ex.code())
                            .isEqualTo(candidate.equals(id) ? ApiProblemCode.CONFLICT : ApiProblemCode.RESOURCE_NOT_FOUND));
        }
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        UUID id = newCase();
        Actor first = newActor();
        Actor second = newActor();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> raceClaim(id, first, ready, go));
            var b = executor.submit(() -> raceClaim(id, second, ready, go));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat((a.get(20, TimeUnit.SECONDS) ? 1 : 0) + (b.get(20, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        } finally { go.countDown(); }
        assertThat(jdbc.queryForObject("SELECT version FROM cases WHERE id = ?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM cases WHERE id = ?", UUID.class, id)).isIn(first.id(), second.id());
    }

    private static boolean raceClaim(UUID id, Actor actor, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
        try { execute(id, 0, actor, Action.CLAIM); return true; }
        catch (ApiProblemException ex) {
            assertThat(ex.code()).isEqualTo(ApiProblemCode.CONFLICT);
            return false;
        }
    }

    private static CaseWorkflowTransactions.Result execute(UUID id, long version, Actor actor, Action action) {
        return workflow.execute(id, version,
                state -> new CaseWorkflowTransactions.Command(actor, action, Input.empty()),
                decision -> {}); // This suite isolates persistence; dedicated commands own business effects.
    }

    private static Actor newActor() {
        UUID id = jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, 'Agent', 'USER', TRUE) RETURNING id
                """, UUID.class, UUID.randomUUID() + "@example.test");
        return new Actor(id, true, UserRole.USER, Set.of(), false);
    }

    private static UUID newCase() {
        String suffix = UUID.randomUUID().toString();
        UUID customer = jdbc.queryForObject("INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class, "Customer " + suffix, suffix);
        UUID integration = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', 'Slack', 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, suffix);
        UUID channel = jdbc.queryForObject("""
                INSERT INTO channels (integration_id, external_channel_id, name, customer_id, ignored, grouping_strategy, active)
                VALUES (?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE) RETURNING id
                """, UUID.class, integration, suffix, customer);
        return jdbc.queryForObject("""
                INSERT INTO cases (customer_id, integration_id, channel_id, provider, external_conversation_id)
                VALUES (?, ?, ?, 'SLACK', ?) RETURNING id
                """, UUID.class, customer, integration, channel, suffix);
    }
}
