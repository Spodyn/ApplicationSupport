package com.unifiedsupportinbox.messaging;

import java.time.Instant;
import java.util.UUID;

public record MessageDeliveryChanged(
        UUID messageId,
        UUID caseId,
        MessageDeliveryStatus deliveryStatus,
        String errorCategory,
        String errorCode,
        Instant nextRetryAt,
        String correlationId) {
}
