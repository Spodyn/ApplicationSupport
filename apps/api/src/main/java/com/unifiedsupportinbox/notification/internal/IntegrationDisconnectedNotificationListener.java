package com.unifiedsupportinbox.notification.internal;

import com.unifiedsupportinbox.integration.IntegrationDisconnected;
import com.unifiedsupportinbox.notification.NotificationDeliveryQueue;
import com.unifiedsupportinbox.notification.NotificationDeliveryQueue.NotificationIntent;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Routes redacted provider disconnection signals through durable notification rules. */
@Component
class IntegrationDisconnectedNotificationListener {

    private final NotificationDeliveryQueue notifications;

    IntegrationDisconnectedNotificationListener(NotificationDeliveryQueue notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void disconnected(IntegrationDisconnected event) {
        notifications.enqueue(new NotificationIntent(
                "integration_disconnected:" + event.integrationId() + ":" + event.errorCode(),
                "integration_disconnected",
                "critical",
                "{\"integrationId\":\"" + event.integrationId() + "\",\"provider\":\""
                        + event.provider().name() + "\",\"errorCode\":\"" + event.errorCode() + "\"}",
                UUID.randomUUID().toString()));
    }
}
