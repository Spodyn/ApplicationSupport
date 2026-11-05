package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActors;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.channel.ChannelView;
import com.unifiedsupportinbox.customer.CustomerDirectory;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChannelService {

    private static final String MANAGE_INTEGRATIONS = "manage_integrations";

    private final ChannelRepository channels;
    private final CustomerDirectory customers;
    private final AuditEventStore auditEvents;

    ChannelService(
            ChannelRepository channels,
            CustomerDirectory customers,
            AuditEventStore auditEvents) {
        this.channels = channels;
        this.customers = customers;
        this.auditEvents = auditEvents;
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

    @Transactional
    ChannelView configure(Authentication actor, UUID channelId, UUID customerId, boolean ignored) {
        requireManageIntegrations(actor);
        if (customerId != null && !customers.isActive(customerId)) {
            throw ApiProblemException.validationFailed("Channel customer must be active.");
        }
        try {
            ChannelRecord updated = channels.configure(channelId, customerId, ignored);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("customerId", customerId);
            metadata.put("ignored", ignored);
            auditEvents.append(
                    AuditActors.type(actor),
                    AuditActors.userId(actor),
                    "CHANNEL_CONFIGURATION_UPDATED",
                    "CHANNEL",
                    updated.id(),
                    null,
                    metadata);
            return updated.toView();
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
