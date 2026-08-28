package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import com.unifiedsupportinbox.OutboxEventStore.OutboxEvent;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class OutboxClaimFencingIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private JdbcTemplate jdbcTemplate;
    private OutboxEventStore store;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetDatabase() {
        TestInfrastructure.resetPostgres(POSTGRES);

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbcTemplate = new JdbcTemplate(dataSource);
        store = new OutboxEventStore(jdbcTemplate, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void staleClaimCannotCheckpointOrReleaseAReclaimedEvent() {
        UUID eventId = jdbcTemplate.queryForObject("""
                INSERT INTO outbox_events (
                    type, aggregate_type, aggregate_id, payload_json, status,
                    next_attempt_at, attempts, created_at, published_at, correlation_id
                )
                VALUES ('case.fenced', 'case', ?, '{}'::jsonb, 'PENDING',
                        CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, NULL, 'corr-fencing')
                RETURNING id
                """, UUID.class, UUID.randomUUID());

        OutboxEvent firstClaim = store.claimDue(1, Duration.ofMinutes(5)).getFirst();
        assertThat(firstClaim.id()).isEqualTo(eventId);
        assertThat(firstClaim.attempts()).isEqualTo(1);

        expireClaim(eventId);

        OutboxEvent secondClaim = store.claimDue(1, Duration.ofMinutes(5)).getFirst();
        assertThat(secondClaim.id()).isEqualTo(eventId);
        assertThat(secondClaim.attempts()).isEqualTo(2);

        assertThat(store.markPublished(eventId, firstClaim.attempts())).isFalse();
        assertThat(store.releaseForRetry(eventId, firstClaim.attempts(), Duration.ofSeconds(1))).isFalse();

        OutboxEvent stillOwnedBySecondClaim = store.findById(eventId).orElseThrow();
        assertThat(stillOwnedBySecondClaim.status()).isEqualTo("PROCESSING");
        assertThat(stillOwnedBySecondClaim.attempts()).isEqualTo(2);
        assertThat(stillOwnedBySecondClaim.publishedAt()).isNull();

        assertThat(store.markPublished(eventId, secondClaim.attempts())).isTrue();
        OutboxEvent published = store.findById(eventId).orElseThrow();
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.attempts()).isEqualTo(2);
        assertThat(published.publishedAt()).isNotNull();
    }

    private void expireClaim(UUID eventId) {
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, eventId);
    }
}
