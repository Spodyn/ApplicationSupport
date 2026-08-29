package com.unifiedsupportinbox.provider.slack.internal;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "usi.slack.inbound-worker")
@Validated
record SlackInboundWorkerProperties(
        @Min(1) @Max(500) @DefaultValue("50") int batchSize,
        @NotNull @DefaultValue("5s") Duration baseRetryDelay,
        @NotNull @DefaultValue("5m") Duration maxRetryDelay,
        @Min(1) @Max(100) @DefaultValue("5") int maxAttempts) {

    @AssertTrue(message = "Slack inbound retry durations must be positive and ordered")
    boolean isValidDurations() {
        return positive(baseRetryDelay)
                && positive(maxRetryDelay)
                && baseRetryDelay.compareTo(maxRetryDelay) <= 0;
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
