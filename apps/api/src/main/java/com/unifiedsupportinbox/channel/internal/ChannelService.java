package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.channel.ChannelView;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChannelService {

    private static final String MANAGE_INTEGRATIONS = "manage_integrations";

    private final ChannelRepository channels;

    ChannelService(ChannelRepository channels) {
        this.channels = channels;
    }

    @Transactional(readOnly = true)
    List<ChannelView> list(Authentication actor) {
        requireManageIntegrations(actor);
        return channels.findAll().stream().map(ChannelRecord::toView).toList();
    }

    @Transactional
    ChannelView setIgnored(Authentication actor, UUID channelId, boolean ignored) {
        requireManageIntegrations(actor);
        try {
            return channels.setIgnored(channelId, ignored).toView();
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.notFound("Channel was not found.");
        }
    }

    private static void requireManageIntegrations(Authentication actor) {
        boolean allowed = actor != null
                && actor.isAuthenticated()
                && actor.getAuthorities().stream()
                        .anyMatch(authority -> MANAGE_INTEGRATIONS.equals(authority.getAuthority()));
        if (!allowed) throw ApiProblemException.accessDenied();
    }
}
