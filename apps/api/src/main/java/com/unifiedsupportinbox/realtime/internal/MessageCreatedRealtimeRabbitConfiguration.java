package com.unifiedsupportinbox.realtime.internal;

import com.unifiedsupportinbox.SensitiveDataRedactor;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class MessageCreatedRealtimeRabbitConfiguration {

    static final String QUEUE = "usi.realtime.message-created";
    static final String DEAD_LETTER_EXCHANGE = "usi.realtime.message-created.dlx";
    static final String DEAD_LETTER_QUEUE = "usi.realtime.message-created.dlq";
    static final String DEAD_LETTER_ROUTING_KEY = "realtime.message-created.dead";
    static final String ROUTING_KEY = "message.created";

    @Bean
    Queue messageCreatedRealtimeQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    DirectExchange messageCreatedRealtimeDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue messageCreatedRealtimeDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding messageCreatedRealtimeBinding(
            @Qualifier("messageCreatedRealtimeQueue") Queue queue,
            TopicExchange usiOutboxExchange) {
        return BindingBuilder.bind(queue).to(usiOutboxExchange).with(ROUTING_KEY);
    }

    @Bean
    Binding messageCreatedRealtimeDeadLetterBinding(
            @Qualifier("messageCreatedRealtimeDeadLetterQueue") Queue queue,
            DirectExchange messageCreatedRealtimeDeadLetterExchange) {
        return BindingBuilder.bind(queue)
                .to(messageCreatedRealtimeDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.realtime.message-created-worker",
        name = "enabled",
        havingValue = "true")
class MessageCreatedRealtimeRabbitListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageCreatedRealtimeRabbitListener.class);

    private final ObjectMapper json;
    private final SimpMessagingTemplate messaging;

    MessageCreatedRealtimeRabbitListener(ObjectMapper json, SimpMessagingTemplate messaging) {
        this.json = json;
        this.messaging = messaging;
    }

    @RabbitListener(queues = MessageCreatedRealtimeRabbitConfiguration.QUEUE)
    void onMessageCreated(Message message) {
        ParsedMessageCreated event = parse(message);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", "message.created");
        envelope.put("version", 1);
        envelope.put("entityId", event.messageId().toString());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("correlationId", event.correlationId());
        envelope.put("payload", Map.of(
                "messageId", event.messageId().toString(),
                "caseId", event.caseId().toString()));
        try {
            messaging.convertAndSend(destination(event.caseId()), (Object) envelope);
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Message-created realtime signal could not be published; messageId={}, caseId={}, reason={}",
                    event.messageId(),
                    event.caseId(),
                    SensitiveDataRedactor.safeExceptionMessage(failure));
        }
    }

    static String destination(UUID caseId) {
        return "/topic/cases/" + caseId;
    }

    private ParsedMessageCreated parse(Message message) {
        try {
            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            if (!MessageCreatedRealtimeRabbitConfiguration.ROUTING_KEY.equals(routingKey)) {
                throw new IllegalArgumentException("Unexpected Message-created routing key.");
            }
            JsonNode root = json.readTree(message.getBody());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Event payload must be an object.");
            }
            UUID messageId = requiredUuid(root, "messageId");
            UUID caseId = requiredUuid(root, "caseId");
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
            return new ParsedMessageCreated(messageId, caseId, occurredAt, correlationId);
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new AmqpRejectAndDontRequeueException("Malformed message-created realtime event.", malformed);
        }
    }

    private static UUID requiredUuid(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return UUID.fromString(value.stringValue());
    }

    private record ParsedMessageCreated(
            UUID messageId,
            UUID caseId,
            Instant occurredAt,
            String correlationId) {
    }
}
