package com.unifiedsupportinbox.notification;

import com.unifiedsupportinbox.integration.IntegrationProvider;

/**
 * Provider-specific boundary for escalation notifications. Implementations own provider HTTP
 * details; the notification worker only depends on the provider-neutral delivery gateway.
 */
public interface NotificationProviderClient {

    boolean supports(IntegrationProvider provider);

    boolean supportsTarget(String targetRef);

    ProviderMessageRef send(ProviderNotification notification) throws NotificationProviderException;

    record ProviderNotification(
            String idempotencyKey,
            String targetRef,
            String eventType,
            String severity,
            String payloadJson,
            String correlationId) {
    }

    record ProviderMessageRef(String value) {
    }
}
