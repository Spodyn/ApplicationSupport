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
    private com.unifiedsupportinbox.messaging.OutboundMutationCallbacks mutations;
    @org.springframework.beans.factory.annotation.Autowired
    void mutationCallbacks(com.unifiedsupportinbox.messaging.OutboundMutationCallbacks mutations){this.mutations=mutations;}
    private TeamsTeamMembership membership;
    @org.springframework.beans.factory.annotation.Autowired
    void teamMembership(TeamsTeamMembership membership){this.membership=membership;}
    private com.unifiedsupportinbox.integration.ProviderConnectionConfiguration connections;
    @org.springframework.beans.factory.annotation.Autowired
    void connectionConfiguration(com.unifiedsupportinbox.integration.ProviderConnectionConfiguration connections){this.connections=connections;}
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
            // Resolve provider identity outside the processing transaction; recheck under a shared
            // binding lock inside it so changing the selected team cannot race a business write.
            UUID selectedTeam=membership.authorize(before.integrationId(),json.readTree(before.payloadJson()));
            processor.process(id, "TEAMS_PROCESSING_FAILED", event -> {
                connections.lockTeam(event.integrationId(),selectedTeam);
                if (integrations.findEnabledForProvider(IntegrationProvider.TEAMS).stream()
                        .noneMatch(i -> i.integrationId().equals(event.integrationId()))) {
                    outcomes.mark(id,InboundEventOutcomeStore.INACTIVE_CHANNEL); return;
                }
                if ("channelCreated".equals(json.readTree(event.payloadJson()).path("channelData").path("eventType").asText())) {
                    if(connections.settings(event.integrationId()).automaticDiscovery())connections.requestDiscovery(event.integrationId());
                    outcomes.mark(id,InboundEventOutcomeStore.UNSUPPORTED_PROVIDER_EVENT);return;
                }
                var activity = TeamsActivity.parse(json.readTree(event.payloadJson()), clientId);
                boolean ownMutation=false;
                if(activity==null){activity=TeamsActivity.parseBotMutation(json.readTree(event.payloadJson()),clientId);ownMutation=activity!=null;}
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
                if(activity.mutation()!=InboundMessageCommandHandler.Mutation.CREATE && mutations!=null
                        && mutations.observe(IntegrationProvider.TEAMS,event.integrationId(),activity.channelId(),activity.id(),activity.author(),
                            activity.body(),activity.mutation()==InboundMessageCommandHandler.Mutation.DELETE,activity.occurredAt())) {
                    outcomes.mark(id,InboundEventOutcomeStore.IGNORED_BOT_MESSAGE);return;
                }
                if(ownMutation){outcomes.mark(id,InboundEventOutcomeStore.IGNORED_BOT_MESSAGE);return;}
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
            if (failure instanceof com.unifiedsupportinbox.ApiProblemException) events.moveToDlq(id,"PERMANENT","TEAMS_TEAM_NOT_SELECTED");
            else if (failure instanceof IllegalArgumentException) events.moveToDlq(id,"MALFORMED","TEAMS_MALFORMED_ACTIVITY");
            else if (failed.attempts()>=12) events.moveToDlq(id,"EXHAUSTED","TEAMS_PROCESSING_EXHAUSTED");
            else events.scheduleRetry(id, failure instanceof ContextPending ? "TEAMS_CONTEXT_CONFIGURATION_PENDING" : "TEAMS_PROCESSING_FAILED",
                    Duration.ofSeconds(Math.min(300,5L << Math.min(failed.attempts(),6))));
        }
    }
    private static class ContextPending extends RuntimeException { }
}
