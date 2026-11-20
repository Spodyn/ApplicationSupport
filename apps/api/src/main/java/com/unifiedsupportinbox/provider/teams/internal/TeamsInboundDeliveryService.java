package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.InboundEventStore;
import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.OutboxEventStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TeamsInboundDeliveryService {
    static final String OUTBOX_TYPE="teams.inbound.received";
    private com.unifiedsupportinbox.integration.ProviderConnectionConfiguration connections;
    @org.springframework.beans.factory.annotation.Autowired
    void connectionConfiguration(com.unifiedsupportinbox.integration.ProviderConnectionConfiguration connections){this.connections=connections;}
    @Transactional InboundEvent persistTeamAndWake(UUID integration,UUID team,String key,String payload,String correlation) {
        connections.lockTeam(integration,team);return persistAndWake(integration,key,payload,correlation);
    }
    private final InboundEventStore events;
    private final OutboxEventStore outbox;
    private final com.unifiedsupportinbox.integration.IntegrationHealthReporter health;
    TeamsInboundDeliveryService(InboundEventStore events, OutboxEventStore outbox,
            com.unifiedsupportinbox.integration.IntegrationHealthReporter health) { this.events=events; this.outbox=outbox; this.health=health; }
    @Transactional InboundEvent persistAndWake(UUID integration,String key,String payload,String correlation) {
        var event=events.persistAuthenticated("TEAMS",integration,key,payload,correlation);
        health.providerEventReceived(integration,java.time.Instant.now());
        if (events.reserveWake(event.id())) wake(event);
        return event;
    }
    @Transactional void redispatchDue() { events.reserveDueForDispatch("TEAMS",50).forEach(this::wake); }
    private void wake(InboundEvent event) { outbox.append(OUTBOX_TYPE,"inbound_event",event.id(),
            "{\"inboundEventId\":\""+event.id()+"\"}",event.correlationId()); }
}
