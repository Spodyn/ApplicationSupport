package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.channel.ChannelContextLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ChannelContextLookupAdapter implements ChannelContextLookup {

    private final ChannelRepository channels;

    ChannelContextLookupAdapter(ChannelRepository channels) {
        this.channels = channels;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Context> findById(UUID channelId) {
        return channels.findById(channelId).map(channel -> new Context(
                channel.id(),
                channel.integrationId(),
                channel.provider(),
                channel.externalChannelId(),
                channel.customerId(),
                channel.ignored(),
                channel.groupingStrategy(),
                channel.active()));
    }
}
