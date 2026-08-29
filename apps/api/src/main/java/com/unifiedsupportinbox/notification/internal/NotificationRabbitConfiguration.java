package com.unifiedsupportinbox.notification.internal;

import java.util.UUID;

import com.unifiedsupportinbox.OutboxProperties;
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
class NotificationRabbitConfiguration {

    static final String DELIVERY_QUEUE = "usi.notifications.delivery";
    static final String DEAD_LETTER_EXCHANGE = "usi.notifications.dlx";
    static final String DEAD_LETTER_QUEUE = "usi.notifications.delivery.dlq";
    static final String DEAD_LETTER_ROUTING_KEY = "notification.delivery.dead";

    @Bean
    Queue notificationDeliveryQueue() {
        return QueueBuilder.durable(DELIVERY_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    DirectExchange notificationDeliveryDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue notificationDeliveryDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding notificationDeliveryBinding(
            @Qualifier("notificationDeliveryQueue") Queue queue,
            TopicExchange usiOutboxExchange) {
        return BindingBuilder.bind(queue)
                .to(usiOutboxExchange)
                .with(NotificationDeliveryService.OUTBOX_TYPE);
    }

    @Bean
    Binding notificationDeliveryDeadLetterBinding(
            @Qualifier("notificationDeliveryDeadLetterQueue") Queue queue,
            DirectExchange notificationDeliveryDeadLetterExchange) {
        return BindingBuilder.bind(queue)
                .to(notificationDeliveryDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.notifications.worker",
        name = "enabled",
        havingValue = "true")
class NotificationDeliveryRabbitListener {

    private final NotificationDeliveryWorker worker;
    private final ObjectMapper json;

    NotificationDeliveryRabbitListener(NotificationDeliveryWorker worker, ObjectMapper json) {
        this.worker = worker;
        this.json = json;
    }

    @RabbitListener(queues = NotificationRabbitConfiguration.DELIVERY_QUEUE)
    void onWakeup(Message message) {
        UUID deliveryId;
        try {
            JsonNode root = json.readTree(message.getBody());
            if (root == null || !root.isObject() || root.get("deliveryId") == null
                    || !root.get("deliveryId").isTextual()) {
                throw new IllegalArgumentException("deliveryId is required");
            }
            deliveryId = UUID.fromString(root.get("deliveryId").stringValue());
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new AmqpRejectAndDontRequeueException(
                    "Malformed notification delivery wake-up message.", malformed);
        }
        worker.process(deliveryId);
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.notifications.worker",
        name = "enabled",
        havingValue = "true")
class NotificationDeliveryRedispatchScheduler {

    private final NotificationDeliveryService deliveries;

    NotificationDeliveryRedispatchScheduler(NotificationDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @Scheduled(fixedDelayString = "${usi.notifications.worker.poll-interval:1s}")
    void redispatchDue() {
        deliveries.redispatchDue();
    }
}
