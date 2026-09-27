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

class CaseLifecycleRealtimeRabbitListenerTests {

    private final ObjectMapper json = new ObjectMapper();
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final CaseLifecycleRealtimeRabbitListener listener =
            new CaseLifecycleRealtimeRabbitListener(json, messaging);

    @Test
    void publishesCreatedAsMinimalVersionedHintWithoutBusinessOrProviderData() {
        UUID caseId = UUID.randomUUID();
        listener.onCaseLifecycle(message(
                "case.created",
                "corr-created",
                """
                {"caseId":"%s","reference":"CASE-42","customerId":"customer-example-shape",
                 "ownerUserId":"user-1","externalConversationId":"C123","providerOpaqueField":"not-forwarded"}
                """.formatted(caseId)));

        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSend(eq(CaseLifecycleRealtimeRabbitListener.DESTINATION), envelopeCaptor.capture());
        Map<?, ?> envelope = (Map<?, ?>) envelopeCaptor.getValue();
        assertThat(envelope.get("eventType")).isEqualTo("case.created");
        assertThat(envelope.get("version")).isEqualTo(1);
        assertThat(envelope.get("entityId")).isEqualTo(caseId.toString());
        assertThat(envelope.get("correlationId")).isEqualTo("corr-created");
        assertThat(envelope.get("occurredAt")).isEqualTo("2026-09-27T18:00:00Z");
        assertThat(envelope.get("payload")).isEqualTo(Map.of("caseId", caseId.toString()));
    }

    @Test
    void mapsOnlyDurableGlobalLifecycleTypesToStablePublicRealtimeTypes() {
        assertThat(CaseLifecycleRealtimeRabbitListener.mapEventType("case.created"))
                .isEqualTo("case.created");
        assertThat(CaseLifecycleRealtimeRabbitListener.mapEventType("case.claimed"))
                .isEqualTo("case.claimed");
        assertThat(CaseLifecycleRealtimeRabbitListener.mapEventType("case.updated"))
                .isEqualTo("case.updated");
        assertThat(CaseLifecycleRealtimeRabbitListener.mapEventType("case.sla_changed"))
                .isEqualTo("case.sla_changed");

        assertThatThrownBy(() -> CaseLifecycleRealtimeRabbitListener.mapEventType("case.unread_changed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaseLifecycleRealtimeRabbitListener.mapEventType("case.resolved"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedOrNonGlobalEventsWithoutRequeue() {
        assertThatThrownBy(() -> listener.onCaseLifecycle(message(
                "message.created", "corr", "{\"caseId\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> listener.onCaseLifecycle(message(
                "case.unread_changed", "corr", "{\"caseId\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> listener.onCaseLifecycle(message(
                "case.updated", "corr", "{}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> listener.onCaseLifecycle(message(
                "case.sla_changed", "", "{\"caseId\":\"" + UUID.randomUUID() + "\"}")))
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
