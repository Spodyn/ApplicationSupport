package com.unifiedsupportinbox.notification;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Instant;
import java.util.UUID;

public record NotificationDestinationView(
        UUID id,
        String name,
        IntegrationProvider provider,
        UUID integrationId,
        String targetRef,
        boolean enabled,
        boolean secretConfigured,
        boolean configConfigured,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
