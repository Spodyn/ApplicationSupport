package com.unifiedsupportinbox.realtime.internal;

import com.unifiedsupportinbox.identity.BrowserSessionAccess;
import java.io.IOException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

/** Check both directions, including server broadcasts to an already subscribed socket. */
final class SessionBoundWebSocketHandlerDecorator extends WebSocketHandlerDecorator {
    static final String HTTP_SESSION_ID = "usi.httpSessionId";
    private final BrowserSessionAccess sessions;

    SessionBoundWebSocketHandlerDecorator(WebSocketHandler delegate, BrowserSessionAccess sessions) {
        super(delegate);
        this.sessions = sessions;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!allowed(session)) return;
        super.afterConnectionEstablished(new WebSocketSessionDecorator(session) {
            @Override
            public void sendMessage(WebSocketMessage<?> message) throws IOException {
                if (allowed(this)) super.sendMessage(message);
            }
        });
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        if (allowed(session)) super.handleMessage(session, message);
    }

    private boolean allowed(WebSocketSession session) throws IOException {
        Object id = session.getAttributes().get(HTTP_SESSION_ID);
        try {
            if (id instanceof String sessionId && session.getPrincipal() != null
                    && sessions.isValid(sessionId, session.getPrincipal().getName())) return true;
        } catch (RuntimeException unavailable) {
            // Database/session-store outages cannot turn a stale socket into an access grant.
        }
        session.close(CloseStatus.POLICY_VIOLATION);
        return false;
    }
}
