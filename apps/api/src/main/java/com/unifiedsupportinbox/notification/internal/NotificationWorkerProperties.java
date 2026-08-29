package com.unifiedsupportinbox.notification.internal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "usi.notifications.worker")
@Validated
record NotificationWorkerProperties(
        @Min(1) @Max(500) @DefaultValue("50") int batchSize,
        @NotNull @DefaultValue("30s") Duration claimLease,
        @NotNull @DefaultValue("5s") Duration baseRetryDelay,
        @NotNull @DefaultValue("5m") Duration maxRetryDelay,
        @Min(1) @Max(100) @DefaultValue("5") int maxAttempts) {

    @AssertTrue(message = "notification worker durations must be positive and max retry must not be shorter than base retry")
    boolean isValidDurations() {
        return positive(claimLease)
                && positive(baseRetryDelay)
                && positive(maxRetryDelay)
                && !maxRetryDelay.minus(baseRetryDelay).isNegative();
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
