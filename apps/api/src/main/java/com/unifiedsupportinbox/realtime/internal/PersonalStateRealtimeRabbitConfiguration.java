package com.unifiedsupportinbox.realtime.internal;

import com.unifiedsupportinbox.SensitiveDataRedactor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
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
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class PersonalStateRealtimeRabbitConfiguration {

    static final String QUEUE = "usi.realtime.personal-state";
    static final String DEAD_LETTER_EXCHANGE = "usi.realtime.personal-state.dlx";
    static final String DEAD_LETTER_QUEUE = "usi.realtime.personal-state.dlq";
    static final String DEAD_LETTER_ROUTING_KEY = "realtime.personal-state.dead";
    static final String UNREAD_CHANGED_ROUTING_KEY = "case.unread_changed";
    static final String READ_POSITION_CHANGED_ROUTING_KEY = "case.read_position_changed";

    @Bean
    Queue personalStateRealtimeQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    DirectExchange personalStateRealtimeDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue personalStateRealtimeDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding unreadChangedPersonalRealtimeBinding(
            @Qualifier("personalStateRealtimeQueue") Queue queue,
            TopicExchange usiOutboxExchange) {
        return BindingBuilder.bind(queue).to(usiOutboxExchange).with(UNREAD_CHANGED_ROUTING_KEY);
    }

    @Bean
    Binding readPositionChangedPersonalRealtimeBinding(
            @Qualifier("personalStateRealtimeQueue") Queue queue,
            TopicExchange usiOutboxExchange) {
        return BindingBuilder.bind(queue).to(usiOutboxExchange).with(READ_POSITION_CHANGED_ROUTING_KEY);
    }

    @Bean
    Binding personalStateRealtimeDeadLetterBinding(
            @Qualifier("personalStateRealtimeDeadLetterQueue") Queue queue,
            DirectExchange personalStateRealtimeDeadLetterExchange) {
        return BindingBuilder.bind(queue)
                .to(personalStateRealtimeDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.realtime.case-lifecycle-worker",
        name = "enabled",
        havingValue = "true")
class PersonalStateRealtimeRabbitListener {

    static final String DESTINATION = "/queue/personal";
    private static final Logger LOGGER = LoggerFactory.getLogger(PersonalStateRealtimeRabbitListener.class);

    private final ObjectMapper json;
    private final SimpMessagingTemplate messaging;
    private final JdbcTemplate jdbc;

    PersonalStateRealtimeRabbitListener(ObjectMapper json, SimpMessagingTemplate messaging, JdbcTemplate jdbc) {
        this.json = json;
        this.messaging = messaging;
        this.jdbc = jdbc;
    }

    @RabbitListener(queues = PersonalStateRealtimeRabbitConfiguration.QUEUE)
    void onPersonalState(Message message) {
        ParsedEvent event = parse(message);
        for (UUID userId : recipients(event)) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventType", event.eventType());
            envelope.put("version", 1);
            envelope.put("entityId", event.caseId().toString());
            envelope.put("occurredAt", event.occurredAt().toString());
            envelope.put("correlationId", event.correlationId());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("caseId", event.caseId().toString());
            if (event.messageId() != null) payload.put("messageId", event.messageId().toString());
            envelope.put("payload", payload);

            try {
                messaging.convertAndSendToUser(userId.toString(), DESTINATION, (Object) envelope);
            } catch (RuntimeException failure) {
                LOGGER.warn(
                        "Personal realtime signal could not be published; caseId={}, eventType={}, reason={}",
                        event.caseId(), event.eventType(), SensitiveDataRedactor.safeExceptionMessage(failure));
            }
        }
    }

    private List<UUID> recipients(ParsedEvent event) {
        if (event.userId() != null) return List.of(event.userId());
        return jdbc.queryForList("""
                SELECT user_id
                FROM case_read_states
                WHERE case_id = ?
                ORDER BY user_id
                """, UUID.class, event.caseId());
    }

    private ParsedEvent parse(Message message) {
        try {
            String sourceType = message.getMessageProperties().getReceivedRoutingKey();
            String eventType = mapEventType(sourceType);
            JsonNode root = json.readTree(message.getBody());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Event payload must be an object.");
            }
            UUID caseId = requiredUuid(root, "caseId");
            UUID messageId = optionalUuid(root, "messageId");
            UUID userId = PersonalStateRealtimeRabbitConfiguration.READ_POSITION_CHANGED_ROUTING_KEY.equals(sourceType)
                    ? requiredUuid(root, "userId")
                    : null;

            Object headerCorrelationId = message.getMessageProperties().getHeaders().get("usi_correlation_id");
            String correlationId = headerCorrelationId == null
                    ? message.getMessageProperties().getCorrelationId()
                    : headerCorrelationId.toString();
            if (correlationId == null || correlationId.isBlank() || correlationId.length() > 256) {
                throw new IllegalArgumentException("correlationId is required.");
            }
            Instant occurredAt = message.getMessageProperties().getTimestamp() == null
                    ? Instant.now()
                    : message.getMessageProperties().getTimestamp().toInstant();
            return new ParsedEvent(eventType, caseId, messageId, userId, occurredAt, correlationId);
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new AmqpRejectAndDontRequeueException("Malformed personal-state realtime event.", malformed);
        }
    }

    static String mapEventType(String sourceType) {
        return switch (sourceType) {
            case PersonalStateRealtimeRabbitConfiguration.UNREAD_CHANGED_ROUTING_KEY -> "case.unread_changed";
            case PersonalStateRealtimeRabbitConfiguration.READ_POSITION_CHANGED_ROUTING_KEY ->
                    "case.read_position_changed";
            default -> throw new IllegalArgumentException("Unsupported personal-state event type.");
        };
    }

    private static UUID requiredUuid(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(field + " is required.");
        return UUID.fromString(value.stringValue());
    }

    private static UUID optionalUuid(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : UUID.fromString(value.stringValue());
    }

    private record ParsedEvent(
            String eventType,
            UUID caseId,
            UUID messageId,
            UUID userId,
            Instant occurredAt,
            String correlationId) {
    }
}
