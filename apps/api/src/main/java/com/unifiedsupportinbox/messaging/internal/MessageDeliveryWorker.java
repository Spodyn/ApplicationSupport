package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.SensitiveDataRedactor;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryCommand;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryResult;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.Outcome;
import com.unifiedsupportinbox.messaging.MessageDeliveryStatus;
import com.unifiedsupportinbox.messaging.internal.MessageDeliveryService.DeliveryClaim;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class MessageDeliveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageDeliveryWorker.class);

    private final MessageDeliveryService deliveries;
    private final ObjectProvider<MessageDeliveryProvider> providers;

    MessageDeliveryWorker(
            MessageDeliveryService deliveries,
            ObjectProvider<MessageDeliveryProvider> providers) {
        this.deliveries = deliveries;
        this.providers = providers;
    }

    AttemptResult process(java.util.UUID messageId) {
        DeliveryClaim claim = deliveries.claim(messageId);
        if (claim == null) return AttemptResult.NOT_CLAIMED;

        if (!"ENABLED".equals(claim.message().integrationStatus())) {
            deliveries.markPermanentFailure(claim, "INTEGRATION_DISABLED");
            return AttemptResult.FAILED;
        }
        if (claim.message().provider() != claim.message().integrationProvider()) {
            deliveries.markPermanentFailure(claim, "INTEGRATION_PROVIDER_MISMATCH");
            return AttemptResult.FAILED;
        }

        List<MessageDeliveryProvider> matching = providers.orderedStream()
                .filter(provider -> provider.supports(claim.message().provider()))
                .toList();
        if (matching.size() != 1) {
            return transientFailure(
                    claim,
                    matching.isEmpty() ? "PROVIDER_UNAVAILABLE" : "PROVIDER_AMBIGUOUS",
                    null);
        }

        DeliveryResult result;
        try {
            result = matching.getFirst().deliver(new DeliveryCommand(
                    claim.message().messageId(),
                    claim.message().messageId().toString(),
                    claim.message().caseId(),
                    claim.message().provider(),
                    claim.message().integrationId(),
                    claim.message().externalConversationId(),
                    claim.message().externalThreadKey(),
                    claim.message().body(),
                    claim.message().bodyFormat(),
                    claim.message().correlationId()));
        } catch (RuntimeException providerFailure) {
            LOGGER.warn(
                    "Outbound Message provider call failed; messageId={}, provider={}, attempt={}",
                    claim.message().messageId(),
                    claim.message().provider(),
                    claim.attempt().attemptNo(),
                    SensitiveDataRedactor.safeExceptionMessage(providerFailure));
            return transientFailure(claim, "PROVIDER_EXCEPTION", null);
        }

        if (result == null || result.outcome() == null) {
            return transientFailure(claim, "PROVIDER_INVALID_RESULT", null);
        }
        if (result.outcome() == Outcome.SENT) {
            deliveries.markSuccess(claim, MessageDeliveryStatus.SENT, result.providerMessageRef());
            return AttemptResult.SENT;
        }
        if (result.outcome() == Outcome.DELIVERED) {
            deliveries.markSuccess(claim, MessageDeliveryStatus.DELIVERED, result.providerMessageRef());
            return AttemptResult.DELIVERED;
        }
        if (result.outcome() == Outcome.PERMANENT_FAILURE) {
            deliveries.markPermanentFailure(claim, result.errorCode());
            return AttemptResult.FAILED;
        }
        return transientFailure(claim, result.errorCode(), result.retryAfter());
    }

    private AttemptResult transientFailure(
            DeliveryClaim claim,
            String errorCode,
            Duration retryAfter) {
        Duration delay = retryDelay(claim.attempt().attemptNo(), retryAfter);
        MessageDeliveryService.DeliveryFailureResult outcome =
                deliveries.markTransientFailure(claim, errorCode, delay);
        return outcome == MessageDeliveryService.DeliveryFailureResult.RETRY_SCHEDULED
                ? AttemptResult.RETRY_SCHEDULED
                : AttemptResult.FAILED;
    }

    private Duration retryDelay(int attemptNo, Duration retryAfter) {
        MessageDeliveryWorkerProperties properties = deliveries.properties();
        long multiplier = 1L << Math.min(Math.max(0, attemptNo - 1), 20);
        Duration exponential;
        try {
            exponential = properties.baseRetryDelay().multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            exponential = properties.maxRetryDelay();
        }
        if (exponential.compareTo(properties.maxRetryDelay()) > 0) {
            exponential = properties.maxRetryDelay();
        }

        double jitter = ThreadLocalRandom.current().nextDouble(0.8d, 1.2d);
        long jitteredMillis = Math.max(1L, Math.round(exponential.toMillis() * jitter));
        Duration jittered = Duration.ofMillis(jitteredMillis);
        if (jittered.compareTo(properties.maxRetryDelay()) > 0) {
            jittered = properties.maxRetryDelay();
        }

        if (retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero()
                && retryAfter.compareTo(jittered) > 0) {
            return retryAfter;
        }
        return jittered;
    }

    enum AttemptResult {
        NOT_CLAIMED,
        SENT,
        DELIVERED,
        RETRY_SCHEDULED,
        FAILED
    }
}
