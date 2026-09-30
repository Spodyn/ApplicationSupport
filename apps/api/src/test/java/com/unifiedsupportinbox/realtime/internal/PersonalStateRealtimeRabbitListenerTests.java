package com.unifiedsupportinbox.realtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

class PersonalStateRealtimeRabbitListenerTests {

    private final ObjectMapper json = new ObjectMapper();
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PersonalStateRealtimeRabbitListener listener =
            new PersonalStateRealtimeRabbitListener(json, messaging, jdbc);

    @Test
    void readPositionChangeIsSentOnlyToTheUserNamedByServerSideEvent() {
        UUID caseId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        listener.onPersonalState(message(
                PersonalStateRealtimeRabbitConfiguration.READ_POSITION_CHANGED_ROUTING_KEY,
                "corr-read",
                """
                {"caseId":"%s","messageId":"%s","userId":"%s"}
                """.formatted(caseId, messageId, userId)));

        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSendToUser(
                eq(userId.toString()),
                eq(PersonalStateRealtimeRabbitListener.DESTINATION),
                envelopeCaptor.capture());

        Map<?, ?> envelope = (Map<?, ?>) envelopeCaptor.getValue();
        assertThat(envelope.get("eventType")).isEqualTo("case.read_position_changed");
        assertThat(envelope.get("entityId")).isEqualTo(caseId.toString());
        assertThat(envelope.get("correlationId")).isEqualTo("corr-read");
        assertThat(envelope.get("payload")).isEqualTo(Map.of(
                "caseId", caseId.toString(),
                "messageId", messageId.toString()));
    }

    @Test
    void unreadChangeFansOutOnlyToUsersWithPersonalReadStateForCase() {
        UUID caseId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(caseId)))
                .thenReturn(List.of(first, second));

        listener.onPersonalState(message(
                PersonalStateRealtimeRabbitConfiguration.UNREAD_CHANGED_ROUTING_KEY,
                "corr-unread",
                """
                {"caseId":"%s","messageId":"%s","reason":"CUSTOMER_MESSAGE"}
                """.formatted(caseId, messageId)));

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(messaging, org.mockito.Mockito.times(2)).convertAndSendToUser(
                userCaptor.capture(),
                eq(PersonalStateRealtimeRabbitListener.DESTINATION),
                org.mockito.ArgumentMatchers.any());
        assertThat(userCaptor.getAllValues()).containsExactlyInAnyOrder(first.toString(), second.toString());
    }

    @Test
    void snoozeChangeIsSentOnlyToTheSnoozingUser() {
        UUID caseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String until = "2026-09-30T09:30:00Z";

        listener.onPersonalState(message(
                PersonalStateRealtimeRabbitConfiguration.SNOOZED_ROUTING_KEY,
                "corr-snooze",
                """
                {"caseId":"%s","userId":"%s","snoozedUntil":"%s"}
                """.formatted(caseId, userId, until)));

        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSendToUser(
                eq(userId.toString()),
                eq(PersonalStateRealtimeRabbitListener.DESTINATION),
                envelopeCaptor.capture());
        Map<?, ?> envelope = (Map<?, ?>) envelopeCaptor.getValue();
        assertThat(envelope.get("eventType")).isEqualTo("case.snoozed");
        assertThat(envelope.get("payload")).isEqualTo(Map.of(
                "caseId", caseId.toString(),
                "snoozedUntil", until));
    }

    @Test
    void snoozeCancelIsSentOnlyToTheUserNamedByServerSideEvent() {
        UUID caseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        listener.onPersonalState(message(
                PersonalStateRealtimeRabbitConfiguration.SNOOZE_CANCELLED_ROUTING_KEY,
                "corr-cancel",
                """
                {"caseId":"%s","userId":"%s"}
                """.formatted(caseId, userId)));

        verify(messaging).convertAndSendToUser(
                eq(userId.toString()),
                eq(PersonalStateRealtimeRabbitListener.DESTINATION),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMalformedOrUnsupportedPersonalEventsWithoutRequeue() {
        assertThatThrownBy(() -> listener.onPersonalState(message(
                "case.claimed", "corr", "{\"caseId\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> listener.onPersonalState(message(
                PersonalStateRealtimeRabbitConfiguration.READ_POSITION_CHANGED_ROUTING_KEY,
                "corr",
                "{\"caseId\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> listener.onPersonalState(message(
                PersonalStateRealtimeRabbitConfiguration.SNOOZED_ROUTING_KEY,
                "corr",
                "{\"caseId\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void mapsOnlyPersonalEventTypes() {
        assertThat(PersonalStateRealtimeRabbitListener.mapEventType("case.unread_changed"))
                .isEqualTo("case.unread_changed");
        assertThat(PersonalStateRealtimeRabbitListener.mapEventType("case.read_position_changed"))
                .isEqualTo("case.read_position_changed");
        assertThat(PersonalStateRealtimeRabbitListener.mapEventType("case.snoozed"))
                .isEqualTo("case.snoozed");
        assertThat(PersonalStateRealtimeRabbitListener.mapEventType("case.snooze_cancelled"))
                .isEqualTo("case.snooze_cancelled");
        assertThatThrownBy(() -> PersonalStateRealtimeRabbitListener.mapEventType("case.updated"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Message message(String routingKey, String correlationId, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        properties.setCorrelationId(correlationId);
        properties.setTimestamp(Date.from(Instant.parse("2026-09-30T07:00:00Z")));
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
