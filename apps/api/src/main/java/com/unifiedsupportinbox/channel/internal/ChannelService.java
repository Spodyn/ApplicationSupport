package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActors;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.channel.ChannelView;
import com.unifiedsupportinbox.customer.CustomerDirectory;
import com.unifiedsupportinbox.channel.ChannelGroupingStrategy;
import com.unifiedsupportinbox.integration.IntegrationProvider;
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
    ChannelView update(
            Authentication actor,
            UUID channelId,
            Boolean ignored,
            UUID customerId,
            ChannelGroupingStrategy groupingStrategy) {
        requireManageIntegrations(actor);

        boolean configurationRequested = customerId != null || groupingStrategy != null;
        if (!configurationRequested && ignored == null) {
            throw ApiProblemException.validationFailed("At least one channel setting must be provided.");
        }
        if ((customerId == null) != (groupingStrategy == null)) {
            throw ApiProblemException.validationFailed(
                    "customerId and groupingStrategy must be provided together.");
        }

        ChannelRecord channel = channels.findById(channelId)
                .orElseThrow(() -> ApiProblemException.notFound("Channel was not found."));

        if (configurationRequested) {
            if (!channels.activeCustomerExists(customerId)) {
                throw ApiProblemException.validationFailed("Customer must exist and be active.");
            }
            validateGrouping(channel.provider(), groupingStrategy);
            channel = channels.configure(channelId, customerId, groupingStrategy);
        }
        if (ignored != null) {
            channel = channels.setIgnored(channelId, ignored);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("customerId", channel.customerId());
        metadata.put("ignored", channel.ignored());
        metadata.put("groupingStrategy", channel.groupingStrategy().name());
        auditEvents.append(AuditActors.type(actor), AuditActors.userId(actor), "CHANNEL_CONFIGURATION_UPDATED",
                "CHANNEL", channel.id(), null, metadata);
        return channel.toView();
    }

    private static void validateGrouping(
            IntegrationProvider provider,
            ChannelGroupingStrategy groupingStrategy) {
        boolean supported = switch (provider) {
            case SLACK -> groupingStrategy == ChannelGroupingStrategy.SLACK_ROOT_THREAD;
            case TEAMS -> groupingStrategy == ChannelGroupingStrategy.TEAMS_ROOT_REPLIES;
            case TELEGRAM -> groupingStrategy == ChannelGroupingStrategy.TELEGRAM_TOPIC
                    || groupingStrategy == ChannelGroupingStrategy.TELEGRAM_CHAT_ACTIVE_CASE;
        };
        if (!supported) {
            throw ApiProblemException.validationFailed(
                    "Grouping strategy is not supported by this channel provider.");
        }
    }

    @Transactional
    ChannelView configure(Authentication actor, UUID channelId, UUID customerId, Boolean ignored) {
        requireManageIntegrations(actor);
        if (customerId != null && !customers.isActive(customerId)) {
            throw ApiProblemException.validationFailed("Channel customer must be active.");
        }
        try {
            ChannelRecord existing = channels.findById(channelId)
                    .orElseThrow(() -> ApiProblemException.notFound("Channel was not found."));
            boolean nextIgnored = ignored == null ? existing.ignored() : ignored;
            ChannelRecord updated = channels.configure(channelId, customerId, nextIgnored);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("customerId", customerId);
            metadata.put("ignored", nextIgnored);
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
