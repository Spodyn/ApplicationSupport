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
        String metadataJson,
        UUID defaultCustomerId) {
    public DiscoveredChannel(UUID integrationId,String externalChannelId,String name,ChannelGroupingStrategy groupingStrategy,
            boolean active,Instant lastMessageAt,String metadataJson) {
        this(integrationId,externalChannelId,name,groupingStrategy,active,lastMessageAt,metadataJson,null);
    }
}
