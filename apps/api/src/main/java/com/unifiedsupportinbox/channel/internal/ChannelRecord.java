package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.channel.ChannelGroupingStrategy;
import com.unifiedsupportinbox.channel.ChannelView;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Instant;
import java.util.UUID;

record ChannelRecord(
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
        Instant lastMessageAt,
        String metadataJson) {

    ChannelView toView() {
        return new ChannelView(
                id,
                integrationId,
                provider,
                externalChannelId,
                name,
                customerId,
                customerName,
                ignored,
                groupingStrategy,
                active,
                lastMessageAt);
    }
}
