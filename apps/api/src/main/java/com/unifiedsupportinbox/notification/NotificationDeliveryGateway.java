package com.unifiedsupportinbox.notification;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Duration;
import java.util.UUID;

/**
 * Minimal provider-neutral delivery port owned by the durable worker. Provider-specific adapters
 * are supplied by later provider/gateway tickets. Implementations must honor the stable
 * idempotency key where the remote provider supports idempotent sends.
 */
public interface NotificationDeliveryGateway {

    DeliveryResult deliver(DeliveryCommand command);

    record DeliveryCommand(
            UUID deliveryId,
            String idempotencyKey,
            UUID destinationId,
            IntegrationProvider provider,
            UUID integrationId,
            String targetRef,
            String eventType,
            String severity,
            String payloadJson,
            String correlationId) {
    }

    record DeliveryResult(
            Outcome outcome,
            String providerMessageRef,
            String errorCode,
            Duration retryAfter) {

        public static DeliveryResult sent(String providerMessageRef) {
            return new DeliveryResult(Outcome.SENT, providerMessageRef, null, null);
        }

        public static DeliveryResult transientFailure(String errorCode, Duration retryAfter) {
            return new DeliveryResult(Outcome.TRANSIENT_FAILURE, null, errorCode, retryAfter);
        }

        public static DeliveryResult permanentFailure(String errorCode) {
            return new DeliveryResult(Outcome.PERMANENT_FAILURE, null, errorCode, null);
        }
    }

    enum Outcome {
        SENT,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }
}
