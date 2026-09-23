package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.channel.ChannelIngestionPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ChannelIngestionPolicyAdapter implements ChannelIngestionPolicy {

    private final ChannelRepository channels;

    ChannelIngestionPolicyAdapter(ChannelRepository channels) {
        this.channels = channels;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Decision> resolve(UUID integrationId, String externalChannelId) {
        return channels.findByIntegrationAndExternalChannel(integrationId, externalChannelId)
                .map(channel -> new Decision(channel.id(), channel.ignored(), channel.active()));
    }
}
