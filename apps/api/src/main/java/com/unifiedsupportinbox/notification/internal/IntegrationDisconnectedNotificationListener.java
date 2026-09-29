package com.unifiedsupportinbox.notification.internal;

import com.unifiedsupportinbox.integration.IntegrationDisconnected;
import com.unifiedsupportinbox.notification.NotificationDeliveryQueue;
import com.unifiedsupportinbox.notification.NotificationDeliveryQueue.NotificationIntent;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class IntegrationDisconnectedNotificationListener {

    private final NotificationDeliveryQueue notifications;
    private final ObjectMapper json;

    IntegrationDisconnectedNotificationListener(
            NotificationDeliveryQueue notifications,
            ObjectMapper json) {
        this.notifications = notifications;
        this.json = json;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDisconnected(IntegrationDisconnected event) {
        notifications.enqueue(new NotificationIntent(
                "integration-disconnected:" + event.integrationId() + ":" + event.occurredAt().toEpochMilli(),
                "integration_disconnected",
                "warning",
                payload(event),
                "integration-health-" + event.integrationId()));
    }

    private String payload(IntegrationDisconnected event) {
        try {
            return json.writeValueAsString(Map.of(
                    "integrationId", event.integrationId().toString(),
                    "provider", event.provider().name(),
                    "errorCode", event.errorCode(),
                    "occurredAt", event.occurredAt().toString()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Integration disconnect notification payload could not be serialized.", exception);
        }
    }
}
