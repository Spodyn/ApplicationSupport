package com.unifiedsupportinbox.messaging.internal;

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
class MessageDeliveryRabbitConfiguration {

    static final String DELIVERY_QUEUE = "usi.messages.delivery";
    static final String DEAD_LETTER_EXCHANGE = "usi.messages.dlx";
    static final String DEAD_LETTER_QUEUE = "usi.messages.delivery.dlq";
    static final String DEAD_LETTER_ROUTING_KEY = "message.delivery.dead";

    @Bean
    Queue messageDeliveryQueue() {
        return QueueBuilder.durable(DELIVERY_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    DirectExchange messageDeliveryDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue messageDeliveryDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding messageDeliveryBinding(
            @Qualifier("messageDeliveryQueue") Queue queue,
            TopicExchange usiOutboxExchange) {
        return BindingBuilder.bind(queue)
                .to(usiOutboxExchange)
                .with(SupportSendMessageService.OUTBOX_TYPE);
    }

    @Bean
    Binding messageDeliveryDeadLetterBinding(
            @Qualifier("messageDeliveryDeadLetterQueue") Queue queue,
            DirectExchange messageDeliveryDeadLetterExchange) {
        return BindingBuilder.bind(queue)
                .to(messageDeliveryDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.messages.delivery-worker",
        name = "enabled",
        havingValue = "true")
class MessageDeliveryRabbitListener {

    private final MessageDeliveryWorker worker;
    private final ObjectMapper json;

    MessageDeliveryRabbitListener(MessageDeliveryWorker worker, ObjectMapper json) {
        this.worker = worker;
        this.json = json;
    }

    @RabbitListener(queues = MessageDeliveryRabbitConfiguration.DELIVERY_QUEUE)
    void onWakeup(Message message) {
        UUID messageId;
        try {
            JsonNode root = json.readTree(message.getBody());
            if (root == null || !root.isObject() || root.get("messageId") == null
                    || !root.get("messageId").isTextual()) {
                throw new IllegalArgumentException("messageId is required");
            }
            messageId = UUID.fromString(root.get("messageId").stringValue());
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new AmqpRejectAndDontRequeueException(
                    "Malformed Message delivery wake-up.", malformed);
        }
        worker.process(messageId);
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.messages.delivery-worker",
        name = "enabled",
        havingValue = "true")
class MessageDeliveryRedispatchScheduler {

    private final MessageDeliveryService deliveries;

    MessageDeliveryRedispatchScheduler(MessageDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @Scheduled(fixedDelayString = "${usi.messages.delivery-worker.poll-interval:1s}")
    void redispatchDue() {
        deliveries.redispatchDue();
    }
}
