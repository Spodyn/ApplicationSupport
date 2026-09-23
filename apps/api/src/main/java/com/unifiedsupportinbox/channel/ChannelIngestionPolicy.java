package com.unifiedsupportinbox.channel;

import java.util.Optional;
import java.util.UUID;

/**
 * Provider-neutral channel policy used by inbound adapters before creating business effects.
 */
public interface ChannelIngestionPolicy {

    Optional<Decision> resolve(UUID integrationId, String externalChannelId);

    record Decision(UUID channelId, boolean ignored, boolean active) {

        public boolean ignoredForInbound() {
            return ignored;
        }
    }
}
