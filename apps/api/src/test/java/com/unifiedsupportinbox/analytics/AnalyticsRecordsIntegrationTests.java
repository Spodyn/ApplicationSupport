package com.unifiedsupportinbox.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class AnalyticsRecordsIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static JdbcTemplate jdbc;
    private UUID owner;
    private UUID first;
    private UUID next;
    private UUID followUp;

    @BeforeAll static void start() {
        POSTGRES.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @BeforeEach void fixture() {
        TestInfrastructure.resetPostgres(POSTGRES);
        owner = jdbc.queryForObject("INSERT INTO users(email, display_name, password_hash, role) VALUES ('stats@example.test', 'Stats owner', 'test-only-hash', 'USER') RETURNING id", UUID.class);
        UUID integration = jdbc.queryForObject("INSERT INTO integrations(provider, display_name, status, health) VALUES ('SLACK', 'Stats test', 'ENABLED', 'HEALTHY') RETURNING id", UUID.class);
        UUID channel = jdbc.queryForObject("INSERT INTO channels(integration_id, external_channel_id, name, grouping_strategy) VALUES (?, 'CSTATS', 'actual-channel', 'SLACK_ROOT_THREAD') RETURNING id", UUID.class, integration);
        first = createCase(integration, channel, "VERIFICATION");
        next = createCase(integration, channel, "VERIFICATION");
        followUp = createCase(integration, channel, "WAITING_FOR_CUSTOMER");
        jdbc.update("UPDATE case_sla SET first_response_completed_at = '2020-01-01T00:16:00Z', first_response_breached_at = first_response_due_at WHERE case_id = ?", first);
        jdbc.update("UPDATE case_sla SET first_response_completed_at = '2020-01-01T00:05:00Z' WHERE case_id IN (?, ?)", next, followUp);
        jdbc.update("""
                INSERT INTO case_next_response_cycles(case_id, opening_message_id, started_at, warning_at, due_at, ended_at, outcome)
                VALUES (?, ?, '2020-01-01T01:00:00Z', '2020-01-01T01:22:30Z', '2020-01-01T01:30:00Z', '2020-01-01T01:31:00Z', 'RESPONDED')
                """, next, UUID.randomUUID());
        jdbc.update("INSERT INTO case_operational_deadlines(case_id, kind, started_at, due_at) VALUES (?, 'FOLLOW_UP', '2020-01-01T00:05:00Z', '2020-01-02T00:05:00Z')", followUp);
    }

    private UUID createCase(UUID integration, UUID channel, String status) {
        UUID id = jdbc.queryForObject("INSERT INTO cases(integration_id, channel_id, provider, external_conversation_id, owner_user_id, status, claimed_at, waiting_until) VALUES (?, ?, 'SLACK', ?, ?, ?, CURRENT_TIMESTAMP, CASE WHEN ? = 'WAITING_FOR_CUSTOMER' THEN '2020-01-02T00:05:00Z'::timestamptz END) RETURNING id", UUID.class, integration, channel, UUID.randomUUID().toString(), owner, status, status);
        jdbc.update("""
                INSERT INTO case_sla(case_id, policy_id, first_response_started_at, first_response_due_at,
                    unclaimed_started_at, unclaimed_warning_at, unclaimed_breach_at)
                SELECT ?, id, '2020-01-01T00:00:00Z', '2020-01-01T00:15:00Z',
                    '2020-01-01T00:00:00Z', '2020-01-01T00:10:00Z', '2020-01-01T00:15:00Z'
                FROM sla_policies ORDER BY version LIMIT 1
                """, id);
        return id;
    }

    @Test void coldInteractivePlanDoesNotTriggerExpensiveJitCompilation() throws Exception {
        String explain = jdbc.queryForObject("EXPLAIN (FORMAT JSON) " + AnalyticsRecordsController.RECORDS_SQL,
                String.class, "UTC", OffsetDateTime.parse("2026-11-27T10:00:00Z"), true, owner);
        double cost = new ObjectMapper().readTree(explain).get(0).path("Plan").path("Total Cost").asDouble();
        double threshold = Double.parseDouble(jdbc.queryForObject("SHOW jit_above_cost", String.class));
        // Fresh, unanalyzed catalogs reproduce the inflated projection-join estimate.
        // Check the plan budget, not machine-dependent elapsed milliseconds or altered server settings.
        assertThat(cost).isLessThan(threshold);
    }

    @Test void factsPreserveFrtNrtFollowUpAndOwnerVisibility() {
        var controller = new AnalyticsRecordsController(jdbc, new StatisticsOverviewRepository(jdbc));
        var global = controller.records(new UsernamePasswordAuthenticationToken(owner.toString(), "unused",
                List.of(new SimpleGrantedAuthority("view_global_statistics"))));
        assertThat(global.records()).hasSize(3);
        var firstFact = global.records().stream().filter(value -> value.id().equals(first)).findFirst().orElseThrow();
        assertThat(firstFact.firstResponseOutcome()).isEqualTo("breached");
        assertThat(firstFact.historicalSupportBreach()).isTrue();
        assertThat(firstFact.historicalNextResponseBreach()).isFalse();
        var nextFact = global.records().stream().filter(value -> value.id().equals(next)).findFirst().orElseThrow();
        assertThat(nextFact.firstResponseOutcome()).isEqualTo("met");
        assertThat(nextFact.historicalSupportBreach()).isTrue();
        assertThat(nextFact.historicalNextResponseBreach()).isTrue();
        var followFact = global.records().stream().filter(value -> value.id().equals(followUp)).findFirst().orElseThrow();
        assertThat(followFact.operationalDeadlineState()).isEqualTo("FOLLOW_UP_DUE");
        assertThat(followFact.historicalSupportBreach()).isFalse();
        assertThat(followFact.historicalNextResponseBreach()).isFalse();
        var outsider = controller.records(new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), "unused", List.of()));
        assertThat(outsider.records()).isEmpty();
        var own = controller.records(new UsernamePasswordAuthenticationToken(owner.toString(), "unused", List.of()));
        assertThat(own.records()).containsExactlyElementsOf(global.records());
    }
}
