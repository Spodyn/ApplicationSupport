package com.unifiedsupportinbox.messaging;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Duration;
import java.util.UUID;

/**
 * Provider-specific outbound Message boundary. Implementations perform provider I/O and must
 * honor {@link DeliveryCommand#idempotencyKey()} for retries of the same logical Message.
 */
public interface MessageDeliveryProvider {

    boolean supports(IntegrationProvider provider);

    DeliveryResult deliver(DeliveryCommand command);

    record DeliveryCommand(
            UUID messageId,
            String idempotencyKey,
            UUID caseId,
            IntegrationProvider provider,
            UUID integrationId,
            String externalConversationId,
            String externalThreadKey,
            String body,
            MessageBodyFormat bodyFormat,
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

        public static DeliveryResult delivered(String providerMessageRef) {
            return new DeliveryResult(Outcome.DELIVERED, providerMessageRef, null, null);
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
        DELIVERED,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }
}
