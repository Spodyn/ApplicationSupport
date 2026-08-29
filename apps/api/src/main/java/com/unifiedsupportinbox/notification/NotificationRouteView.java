package com.unifiedsupportinbox.notification;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.util.List;
import java.util.UUID;

public record NotificationRouteView(
        UUID destinationId,
        UUID ruleId,
        IntegrationProvider provider,
        UUID integrationId,
        String targetRef,
        List<String> eventTypes,
        List<String> severityFilters,
        long destinationVersion,
        long ruleVersion) {
    public NotificationRouteView {
        eventTypes = List.copyOf(eventTypes);
        severityFilters = List.copyOf(severityFilters);
    }
}
