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
class CaseLifecycleRealtimeRabbitConfiguration {

    static final String QUEUE = "usi.realtime.case-lifecycle";
    static final String DEAD_LETTER_EXCHANGE = "usi.realtime.case-lifecycle.dlx";
    static final String DEAD_LETTER_QUEUE = "usi.realtime.case-lifecycle.dlq";
    static final String DEAD_LETTER_ROUTING_KEY = "realtime.case-lifecycle.dead";
    static final String CASE_EVENT_PATTERN = "case.*";

    @Bean
    Queue caseLifecycleRealtimeQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    DirectExchange caseLifecycleRealtimeDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue caseLifecycleRealtimeDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding caseLifecycleRealtimeBinding(
            @Qualifier("caseLifecycleRealtimeQueue") Queue queue,
            TopicExchange usiOutboxExchange) {
        return BindingBuilder.bind(queue).to(usiOutboxExchange).with(CASE_EVENT_PATTERN);
    }

    @Bean
    Binding caseLifecycleRealtimeDeadLetterBinding(
            @Qualifier("caseLifecycleRealtimeDeadLetterQueue") Queue queue,
            DirectExchange caseLifecycleRealtimeDeadLetterExchange) {
        return BindingBuilder.bind(queue)
                .to(caseLifecycleRealtimeDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.realtime.case-lifecycle-worker",
        name = "enabled",
        havingValue = "true")
class CaseLifecycleRealtimeRabbitListener {

    static final String DESTINATION = "/topic/cases";
    private static final Logger LOGGER = LoggerFactory.getLogger(CaseLifecycleRealtimeRabbitListener.class);

    private final ObjectMapper json;
    private final SimpMessagingTemplate messaging;

    CaseLifecycleRealtimeRabbitListener(ObjectMapper json, SimpMessagingTemplate messaging) {
        this.json = json;
        this.messaging = messaging;
    }

    @RabbitListener(queues = CaseLifecycleRealtimeRabbitConfiguration.QUEUE)
    void onCaseLifecycle(Message message) {
        ParsedEvent event = parse(message);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", event.eventType());
        envelope.put("version", 1);
        envelope.put("entityId", event.caseId().toString());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("correlationId", event.correlationId());
        envelope.put("payload", Map.of("caseId", event.caseId().toString()));
        try {
            messaging.convertAndSend(DESTINATION, (Object) envelope);
        } catch (RuntimeException failure) {
            // REST/PostgreSQL remain the source of truth. A best-effort websocket hint must not
            // poison or retry the already committed business event indefinitely.
            LOGGER.warn(
                    "Case lifecycle realtime signal could not be published; caseId={}, eventType={}, reason={}",
                    event.caseId(), event.eventType(), SensitiveDataRedactor.safeExceptionMessage(failure));
        }
    }

    private ParsedEvent parse(Message message) {
        try {
            String sourceType = message.getMessageProperties().getReceivedRoutingKey();
            String eventType = mapEventType(sourceType);
            JsonNode root = json.readTree(message.getBody());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Event payload must be an object.");
            }
            JsonNode caseIdNode = root.get("caseId");
            if (caseIdNode == null || !caseIdNode.isTextual()) {
                throw new IllegalArgumentException("caseId is required.");
            }
            UUID caseId = UUID.fromString(caseIdNode.stringValue());
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
            return new ParsedEvent(eventType, caseId, occurredAt, correlationId);
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new AmqpRejectAndDontRequeueException("Malformed case lifecycle realtime event.", malformed);
        }
    }

    static String mapEventType(String sourceType) {
        if (sourceType == null || !sourceType.startsWith("case.") || sourceType.length() <= "case.".length()) {
            throw new IllegalArgumentException("Unsupported case lifecycle event type.");
        }
        return switch (sourceType) {
            case "case.created" -> "case.created";
            case "case.claimed" -> "case.claimed";
            case "case.sla_changed" -> "case.sla_changed";
            default -> "case.updated";
        };
    }

    private record ParsedEvent(
            String eventType,
            UUID caseId,
            Instant occurredAt,
            String correlationId) {
    }
}
