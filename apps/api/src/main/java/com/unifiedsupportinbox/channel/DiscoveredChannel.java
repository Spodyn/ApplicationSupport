package com.unifiedsupportinbox.channel;

import java.time.Instant;
import java.util.UUID;

public record DiscoveredChannel(
        UUID integrationId,
        String externalChannelId,
        String name,
        ChannelGroupingStrategy groupingStrategy,
        boolean active,
        Instant lastMessageAt,
        String metadataJson) {
}
