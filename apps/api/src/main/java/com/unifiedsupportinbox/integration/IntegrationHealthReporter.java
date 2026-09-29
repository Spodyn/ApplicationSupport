package com.unifiedsupportinbox.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * Provider-neutral boundary for reporting integration activity and provider health changes.
 */
public interface IntegrationHealthReporter {

    void providerEventReceived(UUID integrationId, Instant receivedAt);

    void providerFailure(UUID integrationId, String errorCode, FailureKind kind);

    void providerRecovered(UUID integrationId);

    enum FailureKind {
        DEGRADED,
        DISCONNECTED
    }
}
