package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.InboundEventOutcomeStore;
import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.provider.slack.internal.SlackInboundEventHandler.SlackInboundEvent;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "usi.slack.inbound-worker.enabled=false")
@ActiveProfiles("test")
@Import(SlackIgnoredChannelIntegrationTests.HandlerConfiguration.class)
class SlackIgnoredChannelIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @org.springframework.beans.factory.annotation.Autowired
    private SlackInboundDeliveryService deliveries;

    @org.springframework.beans.factory.annotation.Autowired
    private SlackInboundWorker worker;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    private CountingHandler handler;

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
    }

    @AfterAll
    static void stopInfrastructure() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        TestInfrastructure.resetPostgres(POSTGRES);
        handler.reset();
    }

    @Test
    void channelToggleAffectsOnlyFutureInboundEventsAndIgnoredDuplicatesStaySideEffectFree() {
        UUID integrationId = createSlackIntegration();
        UUID channelId = createChannel(integrationId, "C-support", false);

        InboundEvent active = persist(integrationId, "Ev-active", "C-support");
        assertThat(worker.process(active.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(handler.calls()).isEqualTo(1);
        assertThat(outcome(active.id())).isNull();

        jdbc.update("UPDATE channels SET ignored = TRUE WHERE id = ?", channelId);

        InboundEvent ignored = persist(integrationId, "Ev-ignored", "C-support");
        InboundEvent duplicate = persist(integrationId, "Ev-ignored", "C-support");
        assertThat(duplicate.id()).isEqualTo(ignored.id());

        assertThat(worker.process(ignored.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(worker.process(ignored.id())).isEqualTo(SlackInboundWorker.AttemptResult.ALREADY_PROCESSED);

        assertThat(handler.calls()).isEqualTo(1);
        assertThat(outcome(ignored.id())).isEqualTo(InboundEventOutcomeStore.IGNORED_BY_CHANNEL);
        assertThat(status(ignored.id())).isEqualTo("PROCESSED");
        assertThat(attempts(ignored.id())).isEqualTo(1);
    }

    private UUID createSlackIntegration() {
        UUID integrationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integrations (
                    id, provider, display_name, status, health, workspace_external_id
                ) VALUES (?, 'SLACK', 'Test Slack', 'ENABLED', 'HEALTHY', ?)
                """, integrationId, "T-" + integrationId);
        return integrationId;
    }

    private UUID createChannel(UUID integrationId, String externalChannelId, boolean ignored) {
        UUID channelId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, ignored,
                    grouping_strategy, active
                ) VALUES (?, ?, ?, 'support', ?, 'SLACK_ROOT_THREAD', TRUE)
                """, channelId, integrationId, externalChannelId, ignored);
        return channelId;
    }

    private InboundEvent persist(UUID integrationId, String eventId, String channel) {
        String payload = "{\"type\":\"event_callback\",\"event_id\":\"" + eventId
                + "\",\"event\":{\"type\":\"message\",\"channel\":\"" + channel
                + "\",\"text\":\"hello\"}}";
        return deliveries.persistAndWake(integrationId, eventId, payload, "corr-" + eventId);
    }

    private String outcome(UUID id) {
        return jdbc.queryForObject(
                "SELECT processing_outcome FROM inbound_events WHERE id = ?",
                String.class,
                id);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM inbound_events WHERE id = ?", String.class, id);
    }

    private int attempts(UUID id) {
        Integer value = jdbc.queryForObject("SELECT attempts FROM inbound_events WHERE id = ?", Integer.class, id);
        return value == null ? 0 : value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HandlerConfiguration {
        @Bean
        CountingHandler countingHandler() {
            return new CountingHandler();
        }
    }

    static final class CountingHandler implements SlackInboundEventHandler {
        private int calls;

        @Override
        public void handle(SlackInboundEvent event) {
            calls++;
        }

        int calls() {
            return calls;
        }

        void reset() {
            calls = 0;
        }
    }
}
