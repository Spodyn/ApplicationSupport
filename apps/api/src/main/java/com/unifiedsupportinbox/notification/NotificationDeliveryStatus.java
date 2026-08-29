package com.unifiedsupportinbox.notification;

public enum NotificationDeliveryStatus {
    PENDING,
    PROCESSING,
    RETRY_SCHEDULED,
    SENT,
    DLQ,
    CANCELLED
}
