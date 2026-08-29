package com.unifiedsupportinbox.notification.internal;

import com.unifiedsupportinbox.notification.NotificationDeliveryGateway;
import com.unifiedsupportinbox.notification.NotificationDeliveryGateway.DeliveryCommand;
import com.unifiedsupportinbox.notification.NotificationDeliveryGateway.DeliveryResult;
import com.unifiedsupportinbox.notification.NotificationDeliveryGateway.Outcome;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryWorker.class);

    private final NotificationDeliveryService deliveries;
    private final ObjectProvider<NotificationDeliveryGateway> gateways;

    NotificationDeliveryWorker(
            NotificationDeliveryService deliveries,
            ObjectProvider<NotificationDeliveryGateway> gateways) {
        this.deliveries = deliveries;
        this.gateways = gateways;
    }

    AttemptResult process(UUID deliveryId) {
        DeliveryRecord claim = deliveries.claim(deliveryId);
        if (claim == null) return AttemptResult.NOT_CLAIMED;

        if (claim.attempts() > deliveries.properties().maxAttempts()) {
            deliveries.markDlq(claim, "ATTEMPTS_EXHAUSTED", "TRANSIENT", "ATTEMPTS_EXHAUSTED");
            return AttemptResult.DLQ;
        }

        NotificationDeliveryService.RouteState routeState = deliveries.currentRouteState(claim);
        if (routeState != NotificationDeliveryService.RouteState.ENABLED) {
            deliveries.cancelClaim(claim, routeState);
            return AttemptResult.CANCELLED;
        }

        NotificationDeliveryGateway gateway = gateways.getIfUnique();
        if (gateway == null) {
            return transientFailure(claim, "GATEWAY_UNAVAILABLE", null);
        }

        DeliveryResult result;
        try {
            result = gateway.deliver(new DeliveryCommand(
                    claim.id(),
                    claim.id().toString(),
                    claim.destinationId(),
                    claim.provider(),
                    claim.integrationId(),
                    claim.targetRef(),
                    claim.eventType(),
                    claim.severity(),
                    claim.payloadJson(),
                    claim.correlationId()));
        } catch (RuntimeException gatewayFailure) {
            LOGGER.warn(
                    "Notification provider call failed unexpectedly; deliveryId={}, provider={}, attempt={}",
                    claim.id(), claim.provider(), claim.attempts(), gatewayFailure);
            return transientFailure(claim, "GATEWAY_EXCEPTION", null);
        }

        if (result == null || result.outcome() == null) {
            return transientFailure(claim, "GATEWAY_INVALID_RESULT", null);
        }
        if (result.outcome() == Outcome.SENT) {
            if (!deliveries.markSent(claim, result.providerMessageRef())) {
                throw new IllegalStateException(
                        "notification delivery lost PROCESSING ownership after provider success: " + claim.id());
            }
            return AttemptResult.SENT;
        }
        if (result.outcome() == Outcome.PERMANENT_FAILURE) {
            if (!deliveries.markDlq(claim, "PERMANENT_FAILURE", "PERMANENT", result.errorCode())) {
                throw new IllegalStateException(
                        "notification delivery lost PROCESSING ownership while moving to DLQ: " + claim.id());
            }
            return AttemptResult.DLQ;
        }
        return transientFailure(claim, result.errorCode(), result.retryAfter());
    }

    private AttemptResult transientFailure(DeliveryRecord claim, String errorCode, Duration retryAfter) {
        if (claim.attempts() >= deliveries.properties().maxAttempts()) {
            if (!deliveries.markDlq(claim, "ATTEMPTS_EXHAUSTED", "TRANSIENT", errorCode)) {
                throw new IllegalStateException(
                        "notification delivery lost PROCESSING ownership while exhausting retries: " + claim.id());
            }
            return AttemptResult.DLQ;
        }
        Duration delay = retryDelay(claim.attempts(), retryAfter);
        if (!deliveries.scheduleRetry(claim, delay, errorCode)) {
            throw new IllegalStateException(
                    "notification delivery lost PROCESSING ownership while scheduling retry: " + claim.id());
        }
        return AttemptResult.RETRY_SCHEDULED;
    }

    private Duration retryDelay(int attempt, Duration retryAfter) {
        NotificationWorkerProperties properties = deliveries.properties();
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 20);
        Duration exponential;
        try {
            exponential = properties.baseRetryDelay().multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            exponential = properties.maxRetryDelay();
        }
        if (exponential.compareTo(properties.maxRetryDelay()) > 0) {
            exponential = properties.maxRetryDelay();
        }
        if (retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero()
                && retryAfter.compareTo(exponential) > 0) {
            return retryAfter;
        }
        return exponential;
    }

    enum AttemptResult {
        NOT_CLAIMED,
        SENT,
        RETRY_SCHEDULED,
        DLQ,
        CANCELLED
    }
}
