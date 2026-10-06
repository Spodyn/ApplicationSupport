package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class CustomerReplyWaitingServiceIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Autowired private CustomerReplyWaitingService service;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("usi.outbox.relay-enabled", () -> false);
        registry.add("usi.bootstrap-admin.enabled", () -> false);
    }

    @AfterAll
    static void stopInfrastructure() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        TestInfrastructure.resetPostgres(POSTGRES);
    }

    @Test
    void waitingCaseReturnsToNewAndClearsWaitingStateExactlyOnce() {
        UUID caseId = createWaitingCase();
        UUID messageId = UUID.randomUUID();

        boolean transitioned = service.customerReplied(
                caseId,
                messageId,
                Instant.parse("2026-10-06T09:00:00Z"),
                "corr-customer-reply");

        assertThat(transitioned).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cases WHERE id = ?", String.class, caseId))
                .isEqualTo("NEW");
        assertThat(jdbc.queryForObject(
                "SELECT owner_user_id FROM cases WHERE id = ?", UUID.class, caseId))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT waiting_until FROM cases WHERE id = ?", java.time.OffsetDateTime.class, caseId))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM cases WHERE id = ?", Long.class, caseId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'case.updated' AND aggregate_id = ?",
                Integer.class,
                caseId)).isEqualTo(1);

        assertThat(service.customerReplied(
                caseId,
                UUID.randomUUID(),
                Instant.parse("2026-10-06T09:00:01Z"),
                "corr-duplicate")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE type = 'case.updated' AND aggregate_id = ?",
                Integer.class,
                caseId)).isEqualTo(1);
    }

    private UUID createWaitingCase() {
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Waiting customer " + UUID.randomUUID(),
                "waiting-" + UUID.randomUUID());
        UUID integrationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integrations (
                    id, provider, display_name, status, health, workspace_external_id
                ) VALUES (?, 'SLACK', 'Waiting Slack', 'ENABLED', 'HEALTHY', ?)
                """, integrationId, "T-" + integrationId);
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, customer_id, ignored,
                    grouping_strategy, active
                ) VALUES (?, ?, 'C-waiting', 'waiting', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                """, channelId, integrationId, customerId);

        return jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status,
                    owner_user_id, waiting_until, version
                ) VALUES (
                    ?, ?, ?, 'SLACK', 'C-waiting', 'thread-waiting',
                    'WAITING_FOR_CUSTOMER', NULL, CURRENT_TIMESTAMP + INTERVAL '24 hours', 1
                )
                RETURNING id
                """, UUID.class, customerId, integrationId, channelId);
    }
}
