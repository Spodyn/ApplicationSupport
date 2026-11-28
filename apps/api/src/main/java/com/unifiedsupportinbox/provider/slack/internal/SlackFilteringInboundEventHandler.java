package com.unifiedsupportinbox.provider.slack.internal;

import static com.unifiedsupportinbox.InboundEventOutcomeStore.IGNORED_BOT_MESSAGE;
import static com.unifiedsupportinbox.InboundEventOutcomeStore.INACTIVE_CHANNEL;
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
import tools.jackson.databind.JsonNode;

/**
 * Applies configured-channel policy before handing normalized Slack semantics to the provider-neutral
 * inbound boundary.
 */
class SlackFilteringInboundEventHandler implements SlackInboundEventHandler {

    private final ChannelIngestionPolicy channelPolicy;
    private final InboundEventOutcomeStore outcomes;
    private final SlackNormalizer normalizer;
    private final ObjectProvider<InboundMessageCommandHandler> downstreamHandlers;
    private final SlackInboundAttachmentService attachments;
    private final com.unifiedsupportinbox.messaging.OutboundMutationCallbacks mutations;

    SlackFilteringInboundEventHandler(
            ChannelIngestionPolicy channelPolicy,
            InboundEventOutcomeStore outcomes,
            SlackNormalizer normalizer,
            ObjectProvider<InboundMessageCommandHandler> downstreamHandlers,
            SlackInboundAttachmentService attachments) {
        this(channelPolicy,outcomes,normalizer,downstreamHandlers,attachments,null);
    }

    SlackFilteringInboundEventHandler(ChannelIngestionPolicy channelPolicy,InboundEventOutcomeStore outcomes,
            SlackNormalizer normalizer,ObjectProvider<InboundMessageCommandHandler> downstreamHandlers,
            SlackInboundAttachmentService attachments,com.unifiedsupportinbox.messaging.OutboundMutationCallbacks mutations) {
        this.channelPolicy = channelPolicy;
        this.outcomes = outcomes;
        this.normalizer = normalizer;
        this.downstreamHandlers = downstreamHandlers;
        this.attachments = attachments;
        this.mutations = mutations;
    }

    @Override
    public void handle(SlackInboundEvent inbound) {
        JsonNode event = inbound.event();
        JsonNode eventType = event.get("type");
        if (eventType == null || !eventType.isTextual() || eventType.stringValue().isBlank()) {
            throw SlackInboundProcessingException.malformed(
                    "MALFORMED_SLACK_MESSAGE", "Slack event type is required.");
        }
        if (!"message".equals(eventType.stringValue())) {
            outcomes.mark(inbound.inboundEventId(), UNSUPPORTED_PROVIDER_EVENT);
            return;
        }
        String conversationType = event.path("channel_type").asText("");
        if (!"channel".equals(conversationType) && !"group".equals(conversationType)) {
            outcomes.mark(inbound.inboundEventId(), UNSUPPORTED_PROVIDER_EVENT);
            return;
        }

        String externalChannelId = requiredChannel(event);
        Optional<Decision> resolved = channelPolicy.resolve(inbound.integrationId(), externalChannelId);
        if (resolved.isEmpty()) {
            throw SlackInboundProcessingException.transientFailure(
                    "SLACK_CHANNEL_DISCOVERY_PENDING", "Channel membership reconciliation is pending.");
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

        observeSupportMutation(inbound,externalChannelId);
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
        InboundMessageCommandHandler.Command command = ((Accepted) result).command();
        InboundMessageCommandHandler.Result persisted = downstream.handle(command);
        if (command.mutation() == InboundMessageCommandHandler.Mutation.CREATE) {
            attachments.enqueue(command, persisted, SlackFileReference.from(event));
        }
    }

    private void observeSupportMutation(SlackInboundEvent inbound,String channel) {
        if(mutations==null)return;
        var event=inbound.event();
        String subtype=event.path("subtype").asText();
        if(!java.util.Set.of("message_changed","message_deleted").contains(subtype))return;
        boolean deleted=subtype.equals("message_deleted");
        var message=event.path(deleted?"previous_message":"message");
        String sender=message.path("bot_id").asText(null);
        if(sender==null)sender=message.path("user").asText(null);
        String reference=deleted?event.path("deleted_ts").asText(message.path("ts").asText(null)):message.path("ts").asText(null);
        String timestamp=message.path("edited").path("ts").asText(event.path("event_ts").asText(""));
        if(!timestamp.matches("[0-9]{1,12}(\\.[0-9]{1,9})?"))return;
        var decimal=new java.math.BigDecimal(timestamp);
        var occurred=java.time.Instant.ofEpochSecond(decimal.longValue(),decimal.remainder(java.math.BigDecimal.ONE).movePointRight(9).longValue());
        mutations.observe(com.unifiedsupportinbox.integration.IntegrationProvider.SLACK,inbound.integrationId(),channel,reference,sender,
                message.path("text").asText(null),deleted,occurred);
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
            case UNSUPPORTED_CONVERSATION, UNSUPPORTED_EVENT -> UNSUPPORTED_PROVIDER_EVENT;
        };
    }
}
