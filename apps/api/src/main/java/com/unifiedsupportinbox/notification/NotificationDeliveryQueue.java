package com.unifiedsupportinbox.notification;

import java.util.List;

/**
 * Provider-neutral durable notification enqueue boundary. Producers supply one stable business
 * intent key; the implementation fans it out to currently enabled matching routes and deduplicates
 * each route durably.
 */
public interface NotificationDeliveryQueue {

    List<NotificationDeliveryView> enqueue(NotificationIntent intent);

    record NotificationIntent(
            String intentKey,
            String eventType,
            String severity,
            String payloadJson,
            String correlationId) {
    }
}
