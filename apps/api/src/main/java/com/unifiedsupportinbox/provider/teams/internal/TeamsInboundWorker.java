package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.channel.ChannelIngestionPolicy;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Command;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class TeamsInboundWorker {
    private final InboundEventStore events;
    private final InboundEventProcessor processor;
    private final InboundEventOutcomeStore outcomes;
    private final ChannelIngestionPolicy channels;
    private final InboundMessageCommandHandler messages;
    private final TeamsRouteStore routes;
    private final ObjectMapper json;
    private final String clientId;
    private final TeamsInboundAttachments attachments;
    private final com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup integrations;
    TeamsInboundWorker(InboundEventStore events, InboundEventProcessor processor, InboundEventOutcomeStore outcomes,
            ChannelIngestionPolicy channels, InboundMessageCommandHandler messages, TeamsRouteStore routes,
            ObjectMapper json, @Value("${usi.providers.teams.client-id:}") String clientId,
            com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup integrations,TeamsInboundAttachments attachments) {
        this.events=events; this.processor=processor; this.outcomes=outcomes; this.channels=channels;
        this.messages=messages; this.routes=routes; this.json=json; this.clientId=clientId;
        this.integrations=integrations;
        this.attachments=attachments;
    }
    void process(UUID id) {
        var before=events.findById(id).orElse(null);
        if (before==null || !"TEAMS".equals(before.provider()) || !java.util.Set.of("RECEIVED","FAILED").contains(before.status())) return;
        try {
            processor.process(id, "TEAMS_PROCESSING_FAILED", event -> {
                if (integrations.findEnabledForProvider(IntegrationProvider.TEAMS).stream()
                        .noneMatch(i -> i.integrationId().equals(event.integrationId()))) {
                    outcomes.mark(id,InboundEventOutcomeStore.INACTIVE_CHANNEL); return;
                }
                var activity = TeamsActivity.parse(json.readTree(event.payloadJson()), clientId);
                if (activity==null) { outcomes.mark(id, InboundEventOutcomeStore.UNSUPPORTED_PROVIDER_EVENT); return; }
                if (!routes.isRecovery(id) && !routes.withinMonitoring(event.integrationId(),activity.occurredAt())) {
                    outcomes.mark(id,InboundEventOutcomeStore.UNSUPPORTED_PROVIDER_EVENT); return;
                }
                var channel=channels.resolve(event.integrationId(), activity.channelId()).orElseThrow(ContextPending::new);
                if (channel.ignored()) { outcomes.markIgnoredByChannel(id); return; }
                if (!channel.active()) { outcomes.mark(id, InboundEventOutcomeStore.INACTIVE_CHANNEL); return; }
                if (!channel.customerMapped()) throw new ContextPending();
                // Only explicitly configured standard channels and group chats may enter v1.
                if (!routes.approvedContext(event.integrationId(), activity.channelId(), activity.contextType())) {
                    outcomes.mark(id, InboundEventOutcomeStore.UNSUPPORTED_PROVIDER_EVENT); return;
                }
                routes.save(event.integrationId(), activity);
                var command=new Command(id,event.integrationId(),channel.channelId(),IntegrationProvider.TEAMS,
                        event.externalEventId(),activity.channelId(),activity.id(),activity.thread(),activity.author(),
                        activity.body(),activity.mutation(),activity.occurredAt(),event.correlationId(),activity.authorName());
                var persisted=messages.handle(command);
                if(activity.mutation()==InboundMessageCommandHandler.Mutation.CREATE)
                    attachments.enqueue(command,persisted,json.readTree(event.payloadJson()));
            });
        } catch (RuntimeException failure) {
            var failed=events.findById(id).orElseThrow();
            if ("PROCESSED".equals(failed.status()) || "DLQ".equals(failed.status())) return;
            if (failure instanceof IllegalArgumentException) events.moveToDlq(id,"MALFORMED","TEAMS_MALFORMED_ACTIVITY");
            else if (failed.attempts()>=12) events.moveToDlq(id,"EXHAUSTED","TEAMS_PROCESSING_EXHAUSTED");
            else events.scheduleRetry(id, failure instanceof ContextPending ? "TEAMS_CONTEXT_CONFIGURATION_PENDING" : "TEAMS_PROCESSING_FAILED",
                    Duration.ofSeconds(Math.min(300,5L << Math.min(failed.attempts(),6))));
        }
    }
    private static class ContextPending extends RuntimeException { }
}
