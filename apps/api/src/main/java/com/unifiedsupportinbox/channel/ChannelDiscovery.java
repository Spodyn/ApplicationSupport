package com.unifiedsupportinbox.channel;

public interface ChannelDiscovery {
    ChannelView upsert(DiscoveredChannel discoveredChannel);
}
