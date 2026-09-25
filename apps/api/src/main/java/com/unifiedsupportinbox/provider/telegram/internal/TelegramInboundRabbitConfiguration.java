package com.unifiedsupportinbox.provider.telegram.internal;

import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class TelegramInboundRabbitConfiguration {
    static final String INBOUND_QUEUE = "usi.telegram.inbound";
    @Bean Queue telegramInboundQueue() { return QueueBuilder.durable(INBOUND_QUEUE).build(); }
    @Bean Binding telegramInboundBinding(Queue telegramInboundQueue, TopicExchange usiOutboxExchange) {
        return BindingBuilder.bind(telegramInboundQueue).to(usiOutboxExchange).with(TelegramInboundDeliveryService.OUTBOX_TYPE);
    }
}

@Component
@ConditionalOnProperty(prefix = "usi.telegram.inbound-worker", name = "enabled", havingValue = "true")
class TelegramInboundRabbitListener {
    private final TelegramInboundWorker worker;
    private final ObjectMapper json;
    TelegramInboundRabbitListener(TelegramInboundWorker worker, ObjectMapper json) { this.worker = worker; this.json = json; }
    @RabbitListener(queues = TelegramInboundRabbitConfiguration.INBOUND_QUEUE)
    void onWakeup(Message message) {
        try {
            JsonNode root = json.readTree(message.getBody());
            if (root == null || !root.path("inboundEventId").isTextual()) throw new IllegalArgumentException("inboundEventId is required");
            worker.process(UUID.fromString(root.path("inboundEventId").asText()));
        } catch (JacksonException | IllegalArgumentException bad) {
            throw new AmqpRejectAndDontRequeueException("Malformed Telegram inbound wake-up message.", bad);
        }
    }
}
