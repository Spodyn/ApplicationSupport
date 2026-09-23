package com.unifiedsupportinbox.channel;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only module boundary for business flows that need the canonical channel mapping.
 */
public interface ChannelContextLookup {

    Optional<Context> findById(UUID channelId);

    record Context(
            UUID channelId,
            UUID integrationId,
            IntegrationProvider provider,
            String externalChannelId,
            UUID customerId,
            boolean ignored,
            ChannelGroupingStrategy groupingStrategy,
            boolean active) {
    }
}
