package com.unifiedsupportinbox.realtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

class MessageCreatedRealtimeRabbitListenerTests {

    private final ObjectMapper json = new ObjectMapper();
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final MessageCreatedRealtimeRabbitListener listener =
            new MessageCreatedRealtimeRabbitListener(json, messaging);

    @Test
    void publishesMinimalVersionedEventToCaseConversationTopic() {
        UUID caseId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        listener.onMessageCreated(message(
                "message.created",
                "corr-message-created",
                "{\"messageId\":\"" + messageId + "\",\"caseId\":\"" + caseId
                        + "\",\"body\":\"must-not-be-forwarded\"}"));

        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSend(
                eq("/topic/cases/" + caseId),
                envelopeCaptor.capture());

        Map<?, ?> envelope = (Map<?, ?>) envelopeCaptor.getValue();
        assertThat(envelope.get("eventType")).isEqualTo("message.created");
        assertThat(envelope.get("version")).isEqualTo(1);
        assertThat(envelope.get("entityId")).isEqualTo(messageId.toString());
        assertThat(envelope.get("occurredAt")).isEqualTo("2026-09-27T18:00:00Z");
        assertThat(envelope.get("correlationId")).isEqualTo("corr-message-created");
        assertThat(envelope.get("payload")).isEqualTo(Map.of(
                "messageId", messageId.toString(),
                "caseId", caseId.toString()));
    }

    @Test
    void rejectsWrongRoutingKeyOrMalformedPayloadWithoutRequeue() {
        UUID caseId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        assertThatThrownBy(() -> listener.onMessageCreated(message(
                "message.delivery_updated",
                "corr",
                "{\"messageId\":\"" + messageId + "\",\"caseId\":\"" + caseId + "\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> listener.onMessageCreated(message(
                "message.created", "corr", "{}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> listener.onMessageCreated(message(
                "message.created",
                "",
                "{\"messageId\":\"" + messageId + "\",\"caseId\":\"" + caseId + "\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    private static Message message(String routingKey, String correlationId, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        properties.setCorrelationId(correlationId);
        properties.setTimestamp(Date.from(Instant.parse("2026-09-27T18:00:00Z")));
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
