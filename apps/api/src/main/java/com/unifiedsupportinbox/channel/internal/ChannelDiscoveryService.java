package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.channel.ChannelDiscovery;
import com.unifiedsupportinbox.channel.ChannelView;
import com.unifiedsupportinbox.channel.DiscoveredChannel;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChannelDiscoveryService implements ChannelDiscovery {

    private final ChannelRepository channels;

    ChannelDiscoveryService(ChannelRepository channels) {
        this.channels = channels;
    }

    @Override
    @Transactional
    public ChannelView upsert(DiscoveredChannel discoveredChannel) {
        try {
            return channels.upsertDiscovery(discoveredChannel).toView();
        } catch (DataIntegrityViolationException exception) {
            throw ApiProblemException.validationFailed("Channel discovery references an unknown integration or invalid channel state.");
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed(exception.getMessage());
        }
    }
}
