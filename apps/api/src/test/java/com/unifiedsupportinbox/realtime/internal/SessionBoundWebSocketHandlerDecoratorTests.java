package com.unifiedsupportinbox.realtime.internal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.identity.BrowserSessionAccess;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

class SessionBoundWebSocketHandlerDecoratorTests {
    @Test
    void sessionRevocationBlocksAlreadySubscribedOutboundMessagesAndInboundFrames() throws Exception {
        var sessions = mock(BrowserSessionAccess.class);
        var handler = mock(WebSocketHandler.class);
        var socket = mock(WebSocketSession.class);
        when(socket.getAttributes()).thenReturn(Map.of(SessionBoundWebSocketHandlerDecorator.HTTP_SESSION_ID, "server-id"));
        when(socket.getPrincipal()).thenReturn(() -> "user-id");
        when(sessions.isValid("server-id", "user-id")).thenReturn(true);
        var decorator = new SessionBoundWebSocketHandlerDecorator(handler, sessions);
        decorator.afterConnectionEstablished(socket);
        var wrapped = ArgumentCaptor.forClass(WebSocketSession.class);
        verify(handler).afterConnectionEstablished(wrapped.capture());
        assertThat(wrapped.getValue()).isNotSameAs(socket);
        var message = new TextMessage("sensitive customer data");
        wrapped.getValue().sendMessage(message);
        verify(socket).sendMessage(message);
        clearInvocations(socket);
        when(sessions.isValid("server-id", "user-id")).thenReturn(false);
        wrapped.getValue().sendMessage(message);
        decorator.handleMessage(socket, message);
        verify(socket, never()).sendMessage(any());
        verify(handler, never()).handleMessage(any(), any());
        verify(socket, times(2)).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void sessionStoreOutageFailsClosed() throws Exception {
        var sessions = mock(BrowserSessionAccess.class);
        var socket = mock(WebSocketSession.class);
        when(socket.getAttributes()).thenReturn(Map.of(SessionBoundWebSocketHandlerDecorator.HTTP_SESSION_ID, "server-id"));
        when(socket.getPrincipal()).thenReturn(() -> "user-id");
        when(sessions.isValid(anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
        var handler = mock(WebSocketHandler.class);
        new SessionBoundWebSocketHandlerDecorator(handler, sessions).afterConnectionEstablished(socket);
        verify(handler, never()).afterConnectionEstablished(any());
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }
}
