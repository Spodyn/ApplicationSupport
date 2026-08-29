package com.unifiedsupportinbox.realtime.internal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "usi.realtime")
@Validated
public record RealtimeProperties(
        @NotNull @DefaultValue("10s") Duration heartbeat,
        @NotNull @DefaultValue("15s") Duration timeToFirstMessage,
        @Min(1024) @Max(1048576) @DefaultValue("65536") int messageSizeLimit) {

    @AssertTrue(message = "realtime durations must be positive and fit the WebSocket transport limits")
    public boolean isValidDurations() {
        return positive(heartbeat)
                && positive(timeToFirstMessage)
                && heartbeat.toMillis() <= Integer.MAX_VALUE
                && timeToFirstMessage.toMillis() <= Integer.MAX_VALUE;
    }

    long heartbeatMillis() {
        return heartbeat.toMillis();
    }

    int timeToFirstMessageMillis() {
        return Math.toIntExact(timeToFirstMessage.toMillis());
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
