package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.InboundEventOutcomeStore;
import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Command;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(SlackFilteringInboundIntegrationTests.SinkConfiguration.class)
class SlackFilteringInboundIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Autowired
    private SlackInboundDeliveryService deliveries;

    @Autowired
    private SlackInboundWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CapturingInboundMessageHandler sink;

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
        sink.reset();
    }

    @Test
    void supportedHumanMessageReachesProviderNeutralBoundary() {
        UUID integrationId = createSlackIntegration();
        UUID channelId = createChannel(integrationId, "C-support", true, false);
        InboundEvent event = persist(integrationId, "Ev-human", """
                {"type":"event_callback","event_id":"Ev-human","event":{"type":"message","channel":"C-support","channel_type":"channel","user":"U-customer","text":"hello","ts":"1720000000.123456"}}
                """);

        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(outcome(event.id())).isNull();
        assertThat(sink.commands()).hasSize(1);
        assertThat(sink.commands().getFirst().channelId()).isEqualTo(channelId);
        assertThat(sink.commands().getFirst().externalThreadKey()).isEqualTo("1720000000.123456");
    }

    @Test
    void botMessageIsProcessedAsTechnicalIgnoreWithoutBusinessCommand() {
        UUID integrationId = createSlackIntegration();
        createChannel(integrationId, "C-support", true, false);
        InboundEvent event = persist(integrationId, "Ev-bot", """
                {"type":"event_callback","event_id":"Ev-bot","event":{"type":"message","subtype":"bot_message","channel":"C-support","channel_type":"channel","bot_id":"B-usi","text":"our reply","ts":"1720000000.1"}}
                """);

        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(outcome(event.id())).isEqualTo(InboundEventOutcomeStore.IGNORED_BOT_MESSAGE);
        assertThat(sink.commands()).isEmpty();
    }

    @Test
    void unsupportedProviderEventIsMarkedInsteadOfDeadLettered() {
        UUID integrationId = createSlackIntegration();
        InboundEvent event = persist(integrationId, "Ev-reaction", """
                {"type":"event_callback","event_id":"Ev-reaction","event":{"type":"reaction_added","user":"U-customer","event_ts":"1720000000.1"}}
                """);

        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(outcome(event.id())).isEqualTo(InboundEventOutcomeStore.UNSUPPORTED_PROVIDER_EVENT);
        assertThat(sink.commands()).isEmpty();
    }

    @Test
    void unsupportedDirectMessagesDoNotEnterChannelDiscoveryRetry() {
        UUID integrationId = createSlackIntegration();
        InboundEvent event = persist(integrationId, "Ev-dm", """
                {"type":"event_callback","event_id":"Ev-dm","event":{"type":"message","channel":"D-unmonitored","channel_type":"im","user":"U-customer","text":"not supported","ts":"1720000000.1"}}
                """);
        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(outcome(event.id())).isEqualTo(InboundEventOutcomeStore.UNSUPPORTED_PROVIDER_EVENT);
        assertThat(sink.commands()).isEmpty();
    }

    @Test
    void inactiveAndUnmappedChannelsDoNotReachBusinessBoundary() {
        UUID integrationId = createSlackIntegration();
        createChannel(integrationId, "C-inactive", false, false);

        InboundEvent inactive = persist(integrationId, "Ev-inactive", message("Ev-inactive", "C-inactive"));
        InboundEvent unmapped = persist(integrationId, "Ev-unmapped", message("Ev-unmapped", "C-unknown"));

        assertThat(worker.process(inactive.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(worker.process(unmapped.id())).isEqualTo(SlackInboundWorker.AttemptResult.RETRY_SCHEDULED);
        assertThat(outcome(inactive.id())).isEqualTo(InboundEventOutcomeStore.INACTIVE_CHANNEL);
        assertThat(outcome(unmapped.id())).isNull();
        assertThat(sink.commands()).isEmpty();
    }

    @Test
    void malformedSupportedMessageMovesToControlledDlq() {
        UUID integrationId = createSlackIntegration();
        createChannel(integrationId, "C-support", true, false);
        InboundEvent event = persist(integrationId, "Ev-malformed", """
                {"type":"event_callback","event_id":"Ev-malformed","event":{"type":"message","channel":"C-support","channel_type":"channel","user":"U-customer","ts":"1720000000.1"}}
                """);

        assertThat(worker.process(event.id())).isEqualTo(SlackInboundWorker.AttemptResult.DLQ);
        assertThat(status(event.id())).isEqualTo("DLQ");
        assertThat(errorCode(event.id())).isEqualTo("MALFORMED_SLACK_MESSAGE");
        assertThat(sink.commands()).isEmpty();
    }

    @Test void ownBotMutationCallbacksUpdateRetainedSupportContentWithoutBusinessCommandOrFeedbackLoop() {
        UUID integration=createSlackIntegration(),channel=createChannel(integration,"C-support",true,false);
        UUID author=jdbc.queryForObject("INSERT INTO users(email,display_name,role,active) VALUES ('callback@example.test','Author','USER',true) RETURNING id",UUID.class);
        UUID caseId=jdbc.queryForObject("INSERT INTO cases(customer_id,integration_id,channel_id,provider,external_conversation_id) SELECT customer_id,integration_id,id,'SLACK',external_channel_id FROM channels WHERE id=? RETURNING id",UUID.class,channel);
        UUID message=jdbc.queryForObject("INSERT INTO messages(case_id,kind,author_user_id,body,inbound,delivery_status,external_message_id,correlation_id) VALUES (?,'SUPPORT',?,'before',false,'SENT','1790000000.1','callback') RETURNING id",UUID.class,caseId,author);
        jdbc.update("INSERT INTO message_mutation_targets(message_id,provider_message_ref,conversation_id,sender_id) VALUES (?,'1790000000.1','C-support','B-usi')",message);
        for(int index=0;index<2;index++) {
            String payload="""
                    {"type":"event_callback","event_id":"bot-edit-%d","event":{"type":"message","subtype":"message_changed","channel":"C-support","channel_type":"channel",
                     "event_ts":"1790000001.1","message":{"type":"message","bot_id":"B-usi","ts":"1790000000.1","text":"after","edited":{"ts":"1790000001.1"}}}}
                    """.formatted(index);
            var callback=persist(integration,"bot-edit-"+index,payload);assertThat(worker.process(callback.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
            assertThat(outcome(callback.id())).isEqualTo(InboundEventOutcomeStore.IGNORED_BOT_MESSAGE);
        }
        assertThat(jdbc.queryForObject("SELECT body FROM messages WHERE id=?",String.class,message)).isEqualTo("after");
        assertThat(jdbc.queryForObject("SELECT mutation_revision FROM messages WHERE id=?",Long.class,message)).isEqualTo(1);
        var deletion=persist(integration,"bot-delete","""
                {"type":"event_callback","event_id":"bot-delete","event":{"type":"message","subtype":"message_deleted","channel":"C-support","channel_type":"channel",
                 "event_ts":"1790000002.1","deleted_ts":"1790000000.1","previous_message":{"type":"message","bot_id":"B-usi","ts":"1790000000.1","text":"after"}}}
                """);
        assertThat(worker.process(deletion.id())).isEqualTo(SlackInboundWorker.AttemptResult.PROCESSED);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM messages WHERE id=?",Boolean.class,message)).isTrue();
        assertThat(jdbc.queryForObject("SELECT body FROM message_content_versions WHERE message_id=? AND revision=0",String.class,message)).isEqualTo("before");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_mutations",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isEqualTo(1);assertThat(sink.commands()).isEmpty();
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

    private UUID createChannel(UUID integrationId, String externalChannelId, boolean active, boolean ignored) {
        UUID channelId = UUID.randomUUID();
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name) VALUES (?) RETURNING id",
                UUID.class,
                "Customer " + channelId);
        jdbc.update("""
                INSERT INTO channels (
                    id, integration_id, external_channel_id, name, customer_id, ignored,
                    grouping_strategy, active
                ) VALUES (?, ?, ?, 'support', ?, ?, 'SLACK_ROOT_THREAD', ?)
                """, channelId, integrationId, externalChannelId, customerId, ignored, active);
        return channelId;
    }

    private InboundEvent persist(UUID integrationId, String eventId, String payload) {
        return deliveries.persistAndWake(integrationId, eventId, payload.strip(), "corr-" + eventId);
    }

    private static String message(String eventId, String channel) {
        return "{\"type\":\"event_callback\",\"event_id\":\"" + eventId
                + "\",\"event\":{\"type\":\"message\",\"channel\":\"" + channel
                + "\",\"channel_type\":\"channel\",\"user\":\"U-customer\",\"text\":\"hello\",\"ts\":\"1720000000.1\"}}";
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

    private String errorCode(UUID id) {
        return jdbc.queryForObject("SELECT error_code FROM inbound_events WHERE id = ?", String.class, id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SinkConfiguration {
        @Bean
        @Primary
        CapturingInboundMessageHandler capturingInboundMessageHandler() {
            return new CapturingInboundMessageHandler();
        }
    }

    static final class CapturingInboundMessageHandler implements InboundMessageCommandHandler {
        private final List<Command> commands = new ArrayList<>();

        @Override
        public Result handle(Command command) {
            commands.add(command);
            return new Result(UUID.randomUUID(), UUID.randomUUID(), true);
        }

        List<Command> commands() {
            return List.copyOf(commands);
        }

        void reset() {
            commands.clear();
        }
    }
}
