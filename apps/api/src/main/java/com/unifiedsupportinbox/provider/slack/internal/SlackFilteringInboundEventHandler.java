package com.unifiedsupportinbox.provider.slack.internal;

import static com.unifiedsupportinbox.InboundEventOutcomeStore.IGNORED_BOT_MESSAGE;
import static com.unifiedsupportinbox.InboundEventOutcomeStore.INACTIVE_CHANNEL;
import static com.unifiedsupportinbox.InboundEventOutcomeStore.UNMAPPED_CHANNEL;
import static com.unifiedsupportinbox.InboundEventOutcomeStore.UNSUPPORTED_PROVIDER_EVENT;

import com.unifiedsupportinbox.InboundEventOutcomeStore;
import com.unifiedsupportinbox.channel.ChannelIngestionPolicy;
import com.unifiedsupportinbox.channel.ChannelIngestionPolicy.Decision;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.provider.slack.internal.SlackNormalizer.Accepted;
import com.unifiedsupportinbox.provider.slack.internal.SlackNormalizer.FilterReason;
import com.unifiedsupportinbox.provider.slack.internal.SlackNormalizer.Filtered;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Applies configured-channel policy before handing normalized Slack semantics to the provider-neutral
 * inbound boundary.
 */
@Component
class SlackFilteringInboundEventHandler implements SlackInboundEventHandler {

    private final ChannelIngestionPolicy channelPolicy;
    private final InboundEventOutcomeStore outcomes;
    private final SlackNormalizer normalizer;
    private final ObjectProvider<InboundMessageCommandHandler> downstreamHandlers;

    SlackFilteringInboundEventHandler(
            ChannelIngestionPolicy channelPolicy,
            InboundEventOutcomeStore outcomes,
            SlackNormalizer normalizer,
            ObjectProvider<InboundMessageCommandHandler> downstreamHandlers) {
        this.channelPolicy = channelPolicy;
        this.outcomes = outcomes;
        this.normalizer = normalizer;
        this.downstreamHandlers = downstreamHandlers;
    }

    @Override
    public void handle(SlackInboundEvent inbound) {
        String externalChannelId = requiredChannel(inbound.event());
        Optional<Decision> resolved = channelPolicy.resolve(inbound.integrationId(), externalChannelId);
        if (resolved.isEmpty()) {
            outcomes.mark(inbound.inboundEventId(), UNMAPPED_CHANNEL);
            return;
        }

        Decision channel = resolved.orElseThrow();
        if (channel.ignoredForInbound()) {
            outcomes.markIgnoredByChannel(inbound.inboundEventId());
            return;
        }
        if (!channel.active()) {
            outcomes.mark(inbound.inboundEventId(), INACTIVE_CHANNEL);
            return;
        }

        SlackNormalizer.Result result = normalizer.normalize(inbound, channel.channelId());
        if (result instanceof Filtered filtered) {
            outcomes.mark(inbound.inboundEventId(), outcomeFor(filtered.reason()));
            return;
        }

        InboundMessageCommandHandler downstream = downstreamHandlers.getIfUnique();
        if (downstream == null) {
            throw SlackInboundProcessingException.transientFailure(
                    "INBOUND_MESSAGE_HANDLER_UNAVAILABLE",
                    "Provider-neutral inbound message handler is not available.");
        }
        downstream.handle(((Accepted) result).command());
    }

    private static String requiredChannel(JsonNode event) {
        JsonNode channel = event.get("channel");
        if (channel == null || !channel.isTextual() || channel.stringValue().isBlank()) {
            throw SlackInboundProcessingException.malformed(
                    "MALFORMED_SLACK_CHANNEL", "Slack message channel is required.");
        }
        return channel.stringValue();
    }

    private static String outcomeFor(FilterReason reason) {
        return switch (reason) {
            case BOT_OR_APP_MESSAGE -> IGNORED_BOT_MESSAGE;
            case UNSUPPORTED_EVENT -> UNSUPPORTED_PROVIDER_EVENT;
        };
    }
}
