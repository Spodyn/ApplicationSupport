package com.unifiedsupportinbox;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.unifiedsupportinbox.OutboxEventStore.OutboxEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitMqOutboxTransport {

    static final String EVENT_ID_HEADER = "usi_event_id";
    static final String EVENT_TYPE_HEADER = "usi_event_type";
    static final String AGGREGATE_TYPE_HEADER = "usi_aggregate_type";
    static final String AGGREGATE_ID_HEADER = "usi_aggregate_id";
    static final String CORRELATION_ID_HEADER = "usi_correlation_id";

    private final RabbitTemplate rabbitTemplate;
    private final OutboxProperties properties;

    public RabbitMqOutboxTransport(RabbitTemplate rabbitTemplate, OutboxProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * Publishes one already-claimed event and waits for the broker confirm. The call intentionally
     * performs no database work and must run outside an open DB transaction.
     */
    public void publish(OutboxEvent event) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(UTF_8.name());
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setMessageId(event.id().toString());
        messageProperties.setCorrelationId(event.correlationId());
        messageProperties.setTimestamp(Date.from(event.createdAt()));
        messageProperties.setHeader(EVENT_ID_HEADER, event.id().toString());
        messageProperties.setHeader(EVENT_TYPE_HEADER, event.type());
        messageProperties.setHeader(AGGREGATE_TYPE_HEADER, event.aggregateType());
        messageProperties.setHeader(AGGREGATE_ID_HEADER, event.aggregateId().toString());
        messageProperties.setHeader(CORRELATION_ID_HEADER, event.correlationId());

        Message message = new Message(event.payloadJson().getBytes(UTF_8), messageProperties);
        CorrelationData correlationData = new CorrelationData(event.id().toString());
        rabbitTemplate.send(properties.exchange(), event.type(), message, correlationData);

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (correlationData.getReturned() != null) {
                throw new OutboxTransportException(
                        "RabbitMQ returned unroutable outbox event " + event.id());
            }
            if (!confirm.ack()) {
                throw new OutboxTransportException(
                        "RabbitMQ negatively acknowledged outbox event " + event.id()
                                + (confirm.reason() == null ? "" : ": " + confirm.reason()));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new OutboxTransportException(
                    "interrupted while awaiting RabbitMQ confirm for " + event.id(), interrupted);
        } catch (ExecutionException | TimeoutException confirmFailure) {
            throw new OutboxTransportException(
                    "RabbitMQ confirm failed for outbox event " + event.id(), confirmFailure);
        }
    }

    static final class OutboxTransportException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        OutboxTransportException(String message) {
            super(message);
        }

        OutboxTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
