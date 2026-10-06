package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemCode;
import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.cases.CaseResolutionCategory;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class CaseResolveServiceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseResolveService resolves;

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
        resolves = context.getBean(CaseResolveService.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void resolveIsIdempotentPreservesOwnerClearsSnoozesAndPublishesOneLifecycleEvent() {
        UUID owner = newUser(true);
        UUID other = newUser(true);
        UUID caseId = claimedCase(owner);
        jdbc.update("INSERT INTO case_snoozes (case_id, user_id, until_at) VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                caseId, owner);
        jdbc.update("INSERT INTO case_snoozes (case_id, user_id, until_at) VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '2 hours')",
                caseId, other);

        var first = resolves.resolve(caseId, owner, "resolve-key", CaseResolutionCategory.SOLVED, "resolve-correlation");
        var replay = resolves.resolve(caseId, owner, "resolve-key", CaseResolutionCategory.SOLVED, "resolve-correlation");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(first.body().get("status").asString()).isEqualTo("RESOLVED");
        assertThat(first.body().get("ownerUserId").asString()).isEqualTo(owner.toString());
        assertThat(first.body().get("resolutionCategory").asString()).isEqualTo("SOLVED");
        assertThat(first.body().get("version").asLong()).isEqualTo(2L);
        assertThat(first.body().get("resolvedAt").asString()).isNotBlank();

        assertThat(jdbc.queryForObject("SELECT status FROM cases WHERE id = ?", String.class, caseId))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM cases WHERE id = ?", UUID.class, caseId))
                .isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT resolution_category FROM cases WHERE id = ?", String.class, caseId))
                .isEqualTo("SOLVED");
        assertThat(jdbc.queryForObject(
                "SELECT resolved_at IS NOT NULL AND waiting_until IS NULL FROM cases WHERE id = ?",
                Boolean.class, caseId)).isTrue();
        assertThat(count("case_snoozes", "case_id", caseId)).isZero();
        assertThat(count("audit_events", "case_id", caseId)).isEqualTo(1);
        assertThat(count("outbox_events", "aggregate_id", caseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT type FROM outbox_events WHERE aggregate_id = ?", String.class, caseId))
                .isEqualTo("case.updated");
    }

    @Test
    void resolveSupportsNullCategoryAndRejectsNonOwnerInactiveAndInvalidState() {
        UUID owner = newUser(true);
        UUID caseId = claimedCase(owner);

        UUID stranger = newUser(true);
        assertThatThrownBy(() -> resolves.resolve(caseId, stranger, "not-owner", null, "resolve-correlation"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo(ApiProblemCode.ACCESS_DENIED));

        UUID inactiveOwner = newUser(false);
        UUID inactiveCase = claimedCase(inactiveOwner);
        assertThatThrownBy(() -> resolves.resolve(inactiveCase, inactiveOwner, "inactive", null, "resolve-correlation"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo(ApiProblemCode.ACCESS_DENIED));

        var resolved = resolves.resolve(caseId, owner, "no-category", null, "resolve-correlation");
        assertThat(resolved.body().get("resolutionCategory").isNull()).isTrue();

        assertThatThrownBy(() -> resolves.resolve(caseId, owner, "already-terminal", null, "resolve-correlation"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo(ApiProblemCode.CONFLICT));

        UUID newCase = unclaimedCase();
        assertThatThrownBy(() -> resolves.resolve(newCase, owner, "wrong-state", null, "resolve-correlation"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo(ApiProblemCode.CONFLICT));
    }

    @Test
    void idempotencyKeyCannotBeReusedWithAnotherResolutionCategory() {
        UUID owner = newUser(true);
        UUID caseId = claimedCase(owner);
        resolves.resolve(caseId, owner, "same-key", CaseResolutionCategory.DUPLICATE, "resolve-correlation");

        assertThatThrownBy(() -> resolves.resolve(
                caseId, owner, "same-key", CaseResolutionCategory.OTHER, "resolve-correlation"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo(ApiProblemCode.CONFLICT));
    }

    private static int count(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class,
                id);
    }

    private static UUID newUser(boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, 'Agent', 'USER', ?) RETURNING id
                """, UUID.class, UUID.randomUUID() + "@example.test", active);
    }

    private static UUID claimedCase(UUID owner) {
        UUID caseId = unclaimedCase();
        jdbc.update("""
                UPDATE cases
                SET status = 'VERIFICATION', owner_user_id = ?, claimed_at = CURRENT_TIMESTAMP, version = 1
                WHERE id = ?
                """, owner, caseId);
        return caseId;
    }

    private static UUID unclaimedCase() {
        String key = UUID.randomUUID().toString();
        UUID customer = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class, "Customer " + key, key);
        UUID integration = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', 'Slack', 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, key);
        UUID channel = jdbc.queryForObject("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id, ignored, grouping_strategy, active
                )
                VALUES (?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE) RETURNING id
                """, UUID.class, integration, key, customer);
        return jdbc.queryForObject("""
                INSERT INTO cases (customer_id, integration_id, channel_id, provider, external_conversation_id)
                VALUES (?, ?, ?, 'SLACK', ?) RETURNING id
                """, UUID.class, customer, integration, channel, key);
    }
}
