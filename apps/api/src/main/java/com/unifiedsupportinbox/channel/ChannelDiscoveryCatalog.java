package com.unifiedsupportinbox.channel;

import java.util.List;
import java.util.UUID;

/** Reconciles one provider discovery snapshot without deleting historical Channels. */
public interface ChannelDiscoveryCatalog {

    List<ChannelView> replace(UUID integrationId, List<DiscoveredChannel> discoveredChannels);
}
