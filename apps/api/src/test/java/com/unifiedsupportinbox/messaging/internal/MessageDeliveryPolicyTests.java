package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MessageDeliveryPolicyTests {

    @Test
    void frozenDefaultsBoundAutomaticRetriesToEightAttemptsAndTwentyFourHours() {
        MessageDeliveryWorkerProperties properties = new MessageDeliveryWorkerProperties(
                50,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                Duration.ofHours(24),
                8);

        assertThat(properties.isValidDurations()).isTrue();
        assertThat(properties.maxAutomaticAttempts()).isEqualTo(8);
        assertThat(properties.retryWindow()).isEqualTo(Duration.ofHours(24));
    }
}
