package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class SlackResyncRepositoryIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private JdbcTemplate jdbc;
    private SlackResyncRepository repository;
    private UUID integrationId;
    private UUID channelId;

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void setUp() {
        POSTGRES.start();
        TestInfrastructure.resetPostgres(POSTGRES);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new SlackResyncRepository(jdbc);

        integrationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integrations (
                    id, provider, display_name, status, health,
                    workspace_external_id, secret_ref
                ) VALUES (?, 'SLACK', 'Resync Slack', 'ENABLED', 'HEALTHY', ?, 'slack/resync-test')
                """, integrationId, "T-" + integrationId);
        channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, 'C-RESYNC', 'support', FALSE, 'SLACK_ROOT_THREAD', TRUE)
                """, channelId, integrationId);
    }

    @Test
    void activeJobIsDeduplicatedAndAnExpiredRunningLeaseIsRecoveredAfterRestart() {
        SlackResyncRepository.Job first = repository.create(
                integrationId, channelId, "1758900000.000000000", "1758990000.000000000", 100);
        SlackResyncRepository.Job duplicate = repository.create(
                integrationId, channelId, "1758800000.000000000", "1758990000.000000000", 200);

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(duplicate.maxMessages()).isEqualTo(100);

        SlackResyncRepository.Job claimed = repository.claimDue(Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(first.id());
        assertThat(claimed.status()).isEqualTo("RUNNING");
        assertThat(repository.claimDue(Duration.ofMinutes(2))).isEmpty();

        jdbc.update(
                "UPDATE slack_resync_jobs SET updated_at = CURRENT_TIMESTAMP - INTERVAL '5 minutes' WHERE id = ?",
                first.id());
        SlackResyncRepository.Job recovered = repository.claimDue(Duration.ofMinutes(2)).orElseThrow();
        assertThat(recovered.id()).isEqualTo(first.id());
        assertThat(recovered.status()).isEqualTo("RUNNING");
    }

    @Test
    void cursorProgressSurvivesMultipleClaimsAndCompletesDeterministically() {
        SlackResyncRepository.Job created = repository.create(
                integrationId, channelId, "1758900000.000000000", "1758990000.000000000", 20);
        SlackResyncRepository.Job firstClaim = repository.claimDue(Duration.ofMinutes(2)).orElseThrow();
        assertThat(firstClaim.id()).isEqualTo(created.id());

        SlackResyncRepository.Job pageOne = repository.advance(created.id(), "cursor-two", 7, 7, false);
        assertThat(pageOne.status()).isEqualTo("QUEUED");
        assertThat(pageOne.cursor()).isEqualTo("cursor-two");
        assertThat(pageOne.fetchedMessages()).isEqualTo(7);
        assertThat(pageOne.scheduledEvents()).isEqualTo(7);

        SlackResyncRepository.Job secondClaim = repository.claimDue(Duration.ofMinutes(2)).orElseThrow();
        assertThat(secondClaim.cursor()).isEqualTo("cursor-two");
        assertThat(secondClaim.remaining()).isEqualTo(13);

        SlackResyncRepository.Job completed = repository.advance(created.id(), null, 3, 3, true);
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.fetchedMessages()).isEqualTo(10);
        assertThat(repository.claimDue(Duration.ofMinutes(2))).isEmpty();
    }

    @Test
    void retryAfterStateIsNotClaimedBeforeItsDurableDeadline() {
        SlackResyncRepository.Job created = repository.create(
                integrationId, channelId, "1758900000.000000000", "1758990000.000000000", 20);
        repository.claimDue(Duration.ofMinutes(2)).orElseThrow();
        repository.defer(created.id(), "SLACK_RATE_LIMITED", Duration.ofMinutes(2));

        assertThat(repository.claimDue(Duration.ofMinutes(2))).isEmpty();
        SlackResyncRepository.Job waiting = repository.find(integrationId, created.id()).orElseThrow();
        assertThat(waiting.status()).isEqualTo("WAITING");
        assertThat(waiting.lastErrorCode()).isEqualTo("SLACK_RATE_LIMITED");

        jdbc.update(
                "UPDATE slack_resync_jobs SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                created.id());
        assertThat(repository.claimDue(Duration.ofMinutes(2))).isPresent();
    }
}
