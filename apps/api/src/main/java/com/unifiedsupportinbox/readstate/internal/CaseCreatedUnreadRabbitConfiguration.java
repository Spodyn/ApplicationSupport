package com.unifiedsupportinbox.readstate.internal;

import java.util.UUID;
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
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class CaseCreatedUnreadRabbitConfiguration {

    static final String CASE_CREATED_QUEUE = "usi.readstate.case-created";
    static final String DEAD_LETTER_EXCHANGE = "usi.readstate.case-created.dlx";
    static final String DEAD_LETTER_QUEUE = "usi.readstate.case-created.dlq";
    static final String DEAD_LETTER_ROUTING_KEY = "readstate.case-created.dead";
    static final String CASE_CREATED_ROUTING_KEY = "case.created";

    @Bean
    Queue caseCreatedUnreadQueue() {
        return QueueBuilder.durable(CASE_CREATED_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    DirectExchange caseCreatedUnreadDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue caseCreatedUnreadDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding caseCreatedUnreadBinding(
            @Qualifier("caseCreatedUnreadQueue") Queue queue,
            TopicExchange usiOutboxExchange) {
        return BindingBuilder.bind(queue)
                .to(usiOutboxExchange)
                .with(CASE_CREATED_ROUTING_KEY);
    }

    @Bean
    Binding caseCreatedUnreadDeadLetterBinding(
            @Qualifier("caseCreatedUnreadDeadLetterQueue") Queue queue,
            DirectExchange caseCreatedUnreadDeadLetterExchange) {
        return BindingBuilder.bind(queue)
                .to(caseCreatedUnreadDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.readstate.case-created-worker",
        name = "enabled",
        havingValue = "true")
class CaseCreatedUnreadRabbitListener {

    private final CaseCreatedUnreadProjection projection;
    private final ObjectMapper json;

    CaseCreatedUnreadRabbitListener(CaseCreatedUnreadProjection projection, ObjectMapper json) {
        this.projection = projection;
        this.json = json;
    }

    @RabbitListener(queues = CaseCreatedUnreadRabbitConfiguration.CASE_CREATED_QUEUE)
    void onCaseCreated(Message message) {
        UUID caseId = caseId(message);
        try {
            projection.initialize(caseId);
        } catch (IllegalArgumentException invalidCase) {
            throw new AmqpRejectAndDontRequeueException(
                    "case.created references an unknown Case.", invalidCase);
        }
    }

    private UUID caseId(Message message) {
        try {
            JsonNode root = json.readTree(message.getBody());
            if (root == null || !root.isObject() || root.get("caseId") == null
                    || !root.get("caseId").isTextual()) {
                throw new IllegalArgumentException("caseId is required");
            }
            return UUID.fromString(root.get("caseId").stringValue());
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new AmqpRejectAndDontRequeueException(
                    "Malformed case.created unread-projection event.", malformed);
        }
    }
}
