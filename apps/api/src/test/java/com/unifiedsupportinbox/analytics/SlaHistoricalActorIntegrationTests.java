package com.unifiedsupportinbox.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
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
 * Historical actor projection must never follow a later owner reassignment.
 * Audit rows are append-only: this isolated database intentionally needs no
 * mutation-based audit cleanup.
 */
@Tag("integration")
class SlaHistoricalActorIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static SlaAnalyticsRepository samples;

    @BeforeAll
    static void setup() {
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
        samples = context.getBean(SlaAnalyticsRepository.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void responseAndClaimSlaBelongToHistoricActorNotNewCaseOwner() {
        UUID first = user("first@example.com");
        UUID reassigned = user("second@example.com");
        UUID caseId = newCase();

        jdbc.update("""
                UPDATE cases SET status = 'VERIFICATION', owner_user_id = ?,
                    claimed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, reassigned, caseId);
        jdbc.update("""
                INSERT INTO case_sla (
                    case_id, policy_id,
                    first_response_started_at, first_response_due_at, first_response_completed_at,
                    unclaimed_started_at, unclaimed_warning_at, unclaimed_breach_at,
                    unclaimed_completed_at, unclaimed_outcome)
                VALUES (?, (SELECT id FROM sla_policies WHERE active LIMIT 1),
                        now() - INTERVAL '50 minutes', now() + INTERVAL '10 minutes', now(),
                        now() - INTERVAL '50 minutes', now() - INTERVAL '20 minutes',
                        now() + INTERVAL '10 minutes', now(), 'WARNING')
                """, caseId);
        jdbc.update("""
                INSERT INTO messages (
                    case_id, kind, author_user_id, body, inbound, delivery_status, correlation_id)
                VALUES (?, 'SUPPORT', ?, 'First sent human reply', FALSE, 'SENT', ?)
                """, caseId, first, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO audit_events (
                    actor_type, actor_user_id, action, entity_type,
                    entity_id, case_id, correlation_id)
                VALUES ('USER', ?, 'CASE_CLAIM', 'CASE', ?, ?, ?)
                """, first, caseId, caseId, UUID.randomUUID().toString());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var formerOwnerSamples = samples.completed(today, today, "UTC", "SLACK", null, first);
        assertThat(formerOwnerSamples).hasSize(2);
        assertThat(formerOwnerSamples)
                .extracting(SlaAnalyticsService.Sample::type)
                .containsExactlyInAnyOrder(SlaAnalyticsService.Type.FIRST_RESPONSE,
                        SlaAnalyticsService.Type.UNCLAIMED);
        assertThat(formerOwnerSamples)
                .filteredOn(sample -> sample.type() == SlaAnalyticsService.Type.UNCLAIMED)
                .extracting(SlaAnalyticsService.Sample::outcome)
                .containsExactly(SlaAnalyticsService.Outcome.WARNING);

        assertThat(samples.completed(today, today, "UTC", "SLACK", null, reassigned)).isEmpty();
        assertThat(samples.completed(today, today, "UTC", "TEAMS", null, first)).isEmpty();
    }

    private static UUID user(String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, ?, 'USER', FALSE) RETURNING id
                """, UUID.class, email, email);
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
                INSERT INTO cases (customer_id, integration_id, channel_id, provider, external_conversation_id)
                VALUES (?, ?, ?, 'SLACK', ?) RETURNING id
                """, UUID.class, customer, integration, channel, external);
    }
}
