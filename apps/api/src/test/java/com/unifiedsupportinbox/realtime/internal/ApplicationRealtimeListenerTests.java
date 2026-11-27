package com.unifiedsupportinbox.realtime.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

class ApplicationRealtimeListenerTests {
    @Test
    void administrativeSettingsReachOnlyAdminsAndDirectoryHintsContainNoIdentityData() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        UUID admin = UUID.randomUUID(), agent = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call -> {
            RowMapper<?> mapper = call.getArgument(1);
            ResultSet first = mock(ResultSet.class), second = mock(ResultSet.class);
            when(first.getObject("id", UUID.class)).thenReturn(admin);
            when(first.getString("role")).thenReturn("ADMIN");
            when(second.getObject("id", UUID.class)).thenReturn(agent);
            when(second.getString("role")).thenReturn("USER");
            return List.of(mapper.mapRow(first, 0), mapper.mapRow(second, 1));
        });
        var listener = new ApplicationRealtimeListener(jdbc, messaging, new ObjectMapper());
        listener.receive(event("application.settings_changed", "{\"secret\":\"must-never-escape\"}"));
        verify(messaging).convertAndSendToUser(eq(admin.toString()), eq("/queue/application"), argThat((Object value) ->
                ((Map<?, ?>) value).get("payload").equals(Map.of())));
        verify(messaging).convertAndSendToUser(eq(agent.toString()), eq("/queue/application"), argThat((Object value) ->
                ((Map<?, ?>) value).get("eventType").equals("application.catalog_changed")
                        && ((Map<?, ?>) value).get("payload").equals(Map.of())));
        verifyNoMoreInteractions(messaging);
        reset(messaging);
        listener.receive(event("application.identity_changed", "{\"userId\":\"" + admin + "\"}"));
        verify(messaging).convertAndSendToUser(eq(agent.toString()), eq("/queue/application"), argThat((Object value) -> {
            var envelope = (Map<?, ?>) value;
            return envelope.get("eventType").equals("application.directory_changed")
                    && envelope.get("payload").equals(Map.of());
        }));
    }

    private static Message event(String type, String body) {
        var properties = new MessageProperties();
        properties.setReceivedRoutingKey(type);
        properties.setCorrelationId("test");
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
