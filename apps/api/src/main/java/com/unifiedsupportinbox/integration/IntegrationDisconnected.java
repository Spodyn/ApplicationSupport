package com.unifiedsupportinbox.integration;

import java.time.Instant;
import java.util.UUID;

/** Published when an enabled provider integration transitions into a disconnected state. */
public record IntegrationDisconnected(
        UUID integrationId,
        IntegrationProvider provider,
        String errorCode,
        Instant occurredAt) {
}
