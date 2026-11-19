package com.unifiedsupportinbox.provider.teams.internal;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class TeamsInboundRabbitConfiguration {

    static final String INBOUND_QUEUE = "usi.teams.inbound";
    static final String DEAD_LETTER_EXCHANGE = "usi.teams.inbound.dlx";
    static final String DEAD_LETTER_QUEUE = "usi.teams.inbound.dlq";
    static final String DEAD_LETTER_ROUTING_KEY = "teams.inbound.dead";

    @Bean
    Queue teamsInboundQueue() {
        return QueueBuilder.durable(INBOUND_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    DirectExchange teamsInboundDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue teamsInboundDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding teamsInboundBinding(
            @Qualifier("teamsInboundQueue") Queue queue,
            TopicExchange usiOutboxExchange) {
        return BindingBuilder.bind(queue)
                .to(usiOutboxExchange)
                .with(TeamsInboundDeliveryService.OUTBOX_TYPE);
    }

    @Bean
    Binding teamsInboundDeadLetterBinding(
            @Qualifier("teamsInboundDeadLetterQueue") Queue queue,
            DirectExchange teamsInboundDeadLetterExchange) {
        return BindingBuilder.bind(queue)
                .to(teamsInboundDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.teams.inbound-worker",
        name = "enabled",
        havingValue = "true")
class TeamsInboundRabbitListener {

    private final TeamsInboundWorker worker;
    private final ObjectMapper json;

    TeamsInboundRabbitListener(TeamsInboundWorker worker, ObjectMapper json) {
        this.worker = worker;
        this.json = json;
    }

    @RabbitListener(queues = TeamsInboundRabbitConfiguration.INBOUND_QUEUE)
    void onWakeup(Message message) {
        UUID eventId;
        try {
            JsonNode root = json.readTree(message.getBody());
            if (root == null || !root.isObject() || root.get("inboundEventId") == null
                    || !root.get("inboundEventId").isTextual()) {
                throw new IllegalArgumentException("inboundEventId is required");
            }
            eventId = UUID.fromString(root.get("inboundEventId").stringValue());
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new AmqpRejectAndDontRequeueException(
                    "Malformed Teams inbound wake-up message.", malformed);
        }
        worker.process(eventId);
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.teams.inbound-worker",
        name = "enabled",
        havingValue = "true")
class TeamsInboundRedispatchScheduler {

    private final TeamsInboundDeliveryService deliveries;

    TeamsInboundRedispatchScheduler(TeamsInboundDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @Scheduled(fixedDelayString = "${usi.teams.inbound-worker.poll-interval:1s}")
    void redispatchDue() {
        deliveries.redispatchDue();
    }
}
