package com.unifiedsupportinbox.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A force-resolved Case contributes to operational totals but must never
 * inflate an ADMIN's or the current owner's personal agent-performance KPI.
 * This exercises the real PostgreSQL aggregate builder, not a handcrafted sum.
 */
@Tag("integration")
class AnalyticsForceResolveAttributionIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final Timestamp CREATED = Timestamp.from(
            DAY.atTime(9, 0).toInstant(ZoneOffset.UTC));
    private static final Timestamp RESOLVED = Timestamp.from(
            DAY.atTime(12, 0).toInstant(ZoneOffset.UTC));

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startApplication() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class).profiles("test").run(
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
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void forceResolveIsOperationalOnlyWhileOrdinaryResolveCreditsHistoricalActor() {
        UUID agent = user("force-attribution-agent@example.com", "USER");
        UUID admin = user("force-attribution-admin@example.com", "ADMIN");
        UUID normallyResolved = newCase();
        UUID forceResolved = newCase();

        // Both Cases have the same current owner: mutable ownership cannot
        // turn an administrative force-resolve into agent performance.
        for (UUID caseId : new UUID[] { normallyResolved, forceResolved }) {
            jdbc.update("""
                    UPDATE cases SET owner_user_id = ?, status = 'RESOLVED',
                        claimed_at = ?, resolved_at = ? WHERE id = ?
                    """, agent, CREATED, RESOLVED, caseId);
        }
        audit(normallyResolved, agent, "USER", "CASE_RESOLVE");
        audit(forceResolved, admin, "ADMIN", "CASE_FORCE_RESOLVE");

        context.getBean(AnalyticsDailyAggregateUpdater.class).rebuild(DAY, ZoneOffset.UTC);
        UserStatisticsRepository repository = context.getBean(UserStatisticsRepository.class);

        var agentMetrics = repository.find(agent, DAY, DAY, "UTC", null, null).orElseThrow();
        var adminMetrics = repository.find(admin, DAY, DAY, "UTC", null, null).orElseThrow();

        assertThat(agentMetrics.resolved()).isEqualTo(1);
        assertThat(agentMetrics.averageResolutionSeconds()).isEqualTo(10800);
        assertThat(adminMetrics.resolved()).isZero();
        assertThat(adminMetrics.averageResolutionSeconds()).isNull();

        Long operationalTotal = jdbc.queryForObject("""
                SELECT COALESCE(SUM(resolved_count), 0)
                FROM analytics_daily_case_metrics
                WHERE reporting_date = ? AND timezone_id = 'UTC'
                """, Long.class, DAY);
        Long forceOnly = jdbc.queryForObject("""
                SELECT COALESCE(SUM(resolved_count), 0)
                FROM analytics_daily_case_metrics
                WHERE reporting_date = ? AND timezone_id = 'UTC' AND user_id IS NULL
                """, Long.class, DAY);
        assertThat(operationalTotal).isEqualTo(2);
        assertThat(forceOnly).isEqualTo(1);
    }

    private static UUID user(String email, String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, ?, ?, TRUE) RETURNING id
                """, UUID.class, email, email, role);
    }

    private static UUID newCase() {
        String external = UUID.randomUUID().toString();
        UUID customer = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class, "Customer " + external, external);
        UUID integration = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', 'Slack', 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, external);
        UUID channel = jdbc.queryForObject("""
                INSERT INTO channels (integration_id, external_channel_id, name, customer_id, ignored, grouping_strategy, active)
                VALUES (?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE) RETURNING id
                """, UUID.class, integration, external, customer);
        return jdbc.queryForObject("""
                INSERT INTO cases (customer_id, integration_id, channel_id,
                                   provider, external_conversation_id, created_at)
                VALUES (?, ?, ?, 'SLACK', ?, ?) RETURNING id
                """, UUID.class, customer, integration, channel, external, CREATED);
    }

    private static void audit(UUID caseId, UUID actor, String actorType, String action) {
        jdbc.update("""
                INSERT INTO audit_events (
                    actor_type, actor_user_id, action, entity_type, entity_id,
                    case_id, correlation_id, occurred_at)
                VALUES (?, ?, ?, 'CASE', ?, ?, ?, ?)
                """, actorType, actor, action, caseId, caseId,
                UUID.randomUUID().toString(), RESOLVED);
    }
}
