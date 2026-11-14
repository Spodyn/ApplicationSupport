package com.unifiedsupportinbox.messaging.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class DirectMessageRealtime {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectMessageRealtime.class);
    private final SimpMessagingTemplate messaging;

    DirectMessageRealtime(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener
    void afterCommit(Changed event) {
        var envelope = Map.of("eventType", "direct_message.changed", "version", 1,
                "entityId", event.conversationId().toString(), "occurredAt", Instant.now().toString(),
                "correlationId", UUID.randomUUID().toString(), "payload", Map.of());
        publish(event.actor(), envelope);
        if (event.peer() != null) publish(event.peer(), envelope);
    }

    private void publish(UUID user, Map<String, Object> envelope) {
        try {
            messaging.convertAndSendToUser(user.toString(), "/queue/direct-messages", envelope);
        } catch (RuntimeException unavailable) {
            LOGGER.warn("Could not publish a direct message refresh hint after commit.");
        }
    }

    record Changed(UUID conversationId, UUID actor, UUID peer) { }
}
