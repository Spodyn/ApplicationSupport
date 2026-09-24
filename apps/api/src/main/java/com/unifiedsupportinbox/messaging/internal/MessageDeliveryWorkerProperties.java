package com.unifiedsupportinbox.messaging.internal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "usi.messages.delivery-worker")
@Validated
record MessageDeliveryWorkerProperties(
        @Min(1) @Max(500) @DefaultValue("50") int batchSize,
        @NotNull @DefaultValue("30s") Duration claimLease,
        @NotNull @DefaultValue("1s") Duration baseRetryDelay,
        @NotNull @DefaultValue("5m") Duration maxRetryDelay,
        @NotNull @DefaultValue("24h") Duration retryWindow,
        @Min(1) @Max(100) @DefaultValue("8") int maxAutomaticAttempts) {

    @AssertTrue(message = "message delivery worker durations must be positive and max retry must not be shorter than base retry")
    boolean isValidDurations() {
        return positive(claimLease)
                && positive(baseRetryDelay)
                && positive(maxRetryDelay)
                && positive(retryWindow)
                && !maxRetryDelay.minus(baseRetryDelay).isNegative();
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
