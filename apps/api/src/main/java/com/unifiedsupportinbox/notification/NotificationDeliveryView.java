package com.unifiedsupportinbox.notification;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Instant;
import java.util.UUID;

/** Redacted operational view. The notification payload and credential/config references are never exposed here. */
public record NotificationDeliveryView(
        UUID id,
        String deduplicationKey,
        UUID destinationId,
        UUID ruleId,
        IntegrationProvider provider,
        UUID integrationId,
        String targetRef,
        String eventType,
        String severity,
        NotificationDeliveryStatus status,
        int attempts,
        int replayCount,
        Instant nextAttemptAt,
        String lastErrorCategory,
        String lastErrorCode,
        String terminalReason,
        String providerMessageRef,
        String correlationId,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt) {
}
