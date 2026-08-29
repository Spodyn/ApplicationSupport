package com.unifiedsupportinbox.channel;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Instant;
import java.util.UUID;

public record ChannelView(
        UUID id,
        UUID integrationId,
        IntegrationProvider provider,
        String externalChannelId,
        String name,
        UUID customerId,
        String customerName,
        boolean ignored,
        ChannelGroupingStrategy groupingStrategy,
        boolean active,
        Instant lastMessageAt) {
}
