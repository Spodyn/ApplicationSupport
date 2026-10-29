package com.unifiedsupportinbox.readstate.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class CaseReadPositionRealtimeIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseReadPositionService positions;
    private static ObjectMapper json;

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
        jdbc = context.getBean(JdbcTemplate.class);
        positions = context.getBean(CaseReadPositionService.class);
        json = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void clearBusinessRows() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM case_read_states");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM cases");
        jdbc.update("DELETE FROM channels");
        jdbc.update("DELETE FROM integrations");
        jdbc.update("DELETE FROM customers");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void advancingCurrentUsersCursorWritesOnePersonalOutboxEventAndDuplicateDoesNot() throws Exception {
        Fixture fixture = fixture();
        UUID userId = createUser();
        UUID messageId = customerMessage(fixture.caseId(), Instant.parse("2026-09-30T07:10:00Z"));

        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", "corr-read-personal")) {
            assertThat(positions.markRead(fixture.caseId(), userId, messageId).messageId()).isEqualTo(messageId);
            assertThat(positions.markRead(fixture.caseId(), userId, messageId).messageId()).isEqualTo(messageId);
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE type = 'case.read_position_changed' AND aggregate_id = ?
                """, Integer.class, fixture.caseId())).isEqualTo(1);

        String payload = jdbc.queryForObject("""
                SELECT payload_json::text FROM outbox_events
                WHERE type = 'case.read_position_changed' AND aggregate_id = ?
                """, String.class, fixture.caseId());
        JsonNode event = json.readTree(payload);
        assertThat(event.get("caseId").stringValue()).isEqualTo(fixture.caseId().toString());
        assertThat(event.get("userId").stringValue()).isEqualTo(userId.toString());
        assertThat(event.get("messageId").stringValue()).isEqualTo(messageId.toString());
        assertThat(jdbc.queryForObject("""
                SELECT correlation_id FROM outbox_events
                WHERE type = 'case.read_position_changed' AND aggregate_id = ?
                """, String.class, fixture.caseId())).isEqualTo("corr-read-personal");
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class, "Customer " + suffix, "customer-" + suffix);
        UUID integrationId = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', ?, 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, "Slack " + suffix, "workspace-" + suffix);
        UUID channelId = jdbc.queryForObject("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                RETURNING id
                """, UUID.class, integrationId, "channel-" + suffix, customerId);
        UUID caseId = jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status
                ) VALUES (?, ?, ?, 'SLACK', ?, ?, 'NEW')
                RETURNING id
                """, UUID.class, customerId, integrationId, channelId,
                "conversation-" + suffix, "thread-" + suffix);
        return new Fixture(caseId);
    }

    private static UUID createUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, 'Reader', 'USER', TRUE) RETURNING id
                """, UUID.class, "reader-" + suffix + "@example.invalid");
    }

    private static UUID customerMessage(UUID caseId, Instant providerCreatedAt) {
        return jdbc.queryForObject("""
                INSERT INTO messages (
                    case_id, external_message_id, external_thread_key, kind,
                    author_external_id, body, body_format, inbound,
                    provider_created_at, correlation_id
                ) VALUES (?, ?, 'thread', 'CUSTOMER', 'U-customer', 'hello',
                          'PLAIN_TEXT', TRUE, ?, ?)
                RETURNING id
                """, UUID.class, caseId, "provider-" + UUID.randomUUID(),
                Timestamp.from(providerCreatedAt), "corr-message");
    }

    private record Fixture(UUID caseId) {}
}
