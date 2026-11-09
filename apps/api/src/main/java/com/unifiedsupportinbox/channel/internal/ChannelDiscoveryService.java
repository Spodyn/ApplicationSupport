package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.channel.ChannelDiscovery;
import com.unifiedsupportinbox.channel.ChannelDiscoveryCatalog;
import com.unifiedsupportinbox.channel.ChannelView;
import com.unifiedsupportinbox.channel.DiscoveredChannel;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChannelDiscoveryService implements ChannelDiscovery, ChannelDiscoveryCatalog {

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

    @Override
    @Transactional
    public List<ChannelView> replace(UUID integrationId, List<DiscoveredChannel> discoveredChannels) {
        if (integrationId == null) throw ApiProblemException.validationFailed("Integration is required.");
        List<DiscoveredChannel> snapshot = discoveredChannels == null ? List.of() : List.copyOf(discoveredChannels);
        if (snapshot.stream().anyMatch(channel -> !integrationId.equals(channel.integrationId()))) {
            throw ApiProblemException.validationFailed("Every discovered Channel must belong to the requested Integration.");
        }
        try {
            channels.markAllInactive(integrationId);
            return snapshot.stream().map(channel -> channels.upsertDiscovery(channel).toView()).toList();
        } catch (DataIntegrityViolationException exception) {
            throw ApiProblemException.validationFailed("Channel discovery references an unknown integration or invalid channel state.");
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed(exception.getMessage());
        }
    }
}
