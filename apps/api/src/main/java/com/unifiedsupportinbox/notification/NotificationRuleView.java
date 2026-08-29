package com.unifiedsupportinbox.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationRuleView(
        UUID id,
        UUID destinationId,
        String name,
        boolean enabled,
        List<String> eventTypes,
        List<String> severityFilters,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public NotificationRuleView {
        eventTypes = List.copyOf(eventTypes);
        severityFilters = List.copyOf(severityFilters);
    }
}
