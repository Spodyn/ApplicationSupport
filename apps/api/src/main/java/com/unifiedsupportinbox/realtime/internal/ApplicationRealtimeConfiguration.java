package com.unifiedsupportinbox.realtime.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class ApplicationRealtimeConfiguration {
    @Bean
    Queue applicationRealtimeQueue() {
        return QueueBuilder.durable("usi.realtime.application")
                .deadLetterExchange(CaseLifecycleRealtimeRabbitConfiguration.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(CaseLifecycleRealtimeRabbitConfiguration.DEAD_LETTER_ROUTING_KEY).build();
    }

    @Bean
    Binding applicationRealtimeBinding(@Qualifier("applicationRealtimeQueue") Queue queue, TopicExchange usiOutboxExchange) {
        return BindingBuilder.bind(queue).to(usiOutboxExchange).with("application.*");
    }

    @Bean
    @ConditionalOnProperty(prefix = "usi.realtime.case-lifecycle-worker", name = "enabled", havingValue = "true")
    ApplicationRealtimeListener applicationRealtimeListener(JdbcTemplate jdbc, SimpMessagingTemplate messaging, ObjectMapper json) {
        return new ApplicationRealtimeListener(jdbc, messaging, json);
    }
}

class ApplicationRealtimeListener {
    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper json;

    ApplicationRealtimeListener(JdbcTemplate jdbc, SimpMessagingTemplate messaging, ObjectMapper json) {
        this.jdbc = jdbc;
        this.messaging = messaging;
        this.json = json;
    }

    @RabbitListener(queues = "usi.realtime.application")
    void receive(Message message) {
        String type = message.getMessageProperties().getReceivedRoutingKey();
        Map<String, Object> payload;
        UUID affected = null;
        try {
            var source = json.readTree(message.getBody());
            payload = switch (type) {
                case "application.settings_changed" -> Map.of();
                case "application.directory_changed" -> Map.of();
                case "application.identity_changed" -> Map.of("userId", UUID.fromString(source.path("userId").asText()).toString());
                case "application.reminders_changed" -> Map.of("userId", UUID.fromString(source.path("userId").asText()).toString());
                case "application.case_changed" -> Map.of("caseId", UUID.fromString(source.path("caseId").asText()).toString());
                default -> throw new IllegalArgumentException("Unsupported scope");
            };
            if (payload.containsKey("userId")) affected = UUID.fromString((String) payload.get("userId"));
        } catch (RuntimeException malformed) {
            throw new AmqpRejectAndDontRequeueException("Invalid application hint", malformed);
        }
        // Re-evaluate recipients at delivery time. Never broadcast administrative identifiers.
        var recipients = jdbc.query("""
                SELECT id, role FROM users WHERE active
                  AND (valid_from IS NULL OR valid_from <= now())
                  AND (valid_until IS NULL OR valid_until > now())
                """, (rs, row) -> new Recipient(rs.getObject("id", UUID.class), rs.getString("role")));
        for (var recipient : recipients) {
            boolean self = recipient.id().equals(affected);
            boolean admin = recipient.role().equals("ADMIN");
            if (type.equals("application.reminders_changed") && !self) continue;
            String deliveredType = type;
            Map<String, Object> deliveredPayload = payload;
            if (type.equals("application.settings_changed") && !admin) {
                deliveredType = "application.catalog_changed";
                deliveredPayload = Map.of();
            }
            if (type.equals("application.identity_changed") && !self) {
                deliveredType = "application.directory_changed";
                deliveredPayload = Map.of();
            }
            var envelope = Map.of("eventType", deliveredType, "version", 1,
                    "entityId", deliveredPayload.getOrDefault("caseId", recipient.id().toString()),
                    "occurredAt", Instant.now().toString(), "correlationId", message.getMessageProperties().getCorrelationId(),
                    "payload", deliveredPayload);
            messaging.convertAndSendToUser(recipient.id().toString(), "/queue/application", envelope);
        }
    }

    private record Recipient(UUID id, String role) {}
}
