package com.unifiedsupportinbox.realtime.internal;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

final class FirstMessageTimeoutWebSocketHandlerDecorator extends WebSocketHandlerDecorator {

    private static final Logger log =
            LoggerFactory.getLogger(FirstMessageTimeoutWebSocketHandlerDecorator.class);

    private final TaskScheduler taskScheduler;
    private final Duration timeout;
    private final ConcurrentMap<String, ScheduledFuture<?>> pendingTimeouts = new ConcurrentHashMap<>();

    FirstMessageTimeoutWebSocketHandlerDecorator(
            WebSocketHandler delegate,
            TaskScheduler taskScheduler,
            Duration timeout) {
        super(delegate);
        this.taskScheduler = taskScheduler;
        this.timeout = timeout;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);

        ScheduledFuture<?> timeoutTask = taskScheduler.schedule(
                () -> closeIfStillWaiting(session),
                Instant.now().plus(timeout));
        if (timeoutTask == null) {
            throw new IllegalStateException("Unable to schedule realtime first-message timeout.");
        }
        ScheduledFuture<?> previous = pendingTimeouts.put(session.getId(), timeoutTask);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        cancelTimeout(session.getId());
        super.handleMessage(session, message);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        cancelTimeout(session.getId());
        super.handleTransportError(session, exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        cancelTimeout(session.getId());
        super.afterConnectionClosed(session, closeStatus);
    }

    private void closeIfStillWaiting(WebSocketSession session) {
        ScheduledFuture<?> timeoutTask = pendingTimeouts.remove(session.getId());
        if (timeoutTask == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException exception) {
            log.warn("Failed to close WebSocket session {} after first-message timeout", session.getId(), exception);
        }
    }

    private void cancelTimeout(String sessionId) {
        ScheduledFuture<?> timeoutTask = pendingTimeouts.remove(sessionId);
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
    }
}
