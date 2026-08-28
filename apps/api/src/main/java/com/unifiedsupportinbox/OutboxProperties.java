package com.unifiedsupportinbox;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "usi.outbox")
@Validated
public record OutboxProperties(
        @NotBlank @DefaultValue("usi.events") String exchange,
        @Min(1) @Max(500) @DefaultValue("50") int batchSize,
        @NotNull @DefaultValue("30s") Duration claimLease,
        @NotNull @DefaultValue("5s") Duration retryDelay,
        @NotNull @DefaultValue("5s") Duration confirmTimeout) {

    @AssertTrue(message = "outbox durations must be positive")
    public boolean isValidDurations() {
        return positive(claimLease) && positive(retryDelay) && positive(confirmTimeout);
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
