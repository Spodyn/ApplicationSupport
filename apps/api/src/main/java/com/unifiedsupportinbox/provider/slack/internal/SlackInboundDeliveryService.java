package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.InboundEventStore;
import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.OutboxEventStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SlackInboundDeliveryService {

    static final String OUTBOX_TYPE = "slack.inbound.received";
    private static final String AGGREGATE_TYPE = "inbound_event";

    private final InboundEventStore inboundEvents;
    private final OutboxEventStore outbox;
    private final SlackInboundWorkerProperties properties;

    SlackInboundDeliveryService(
            InboundEventStore inboundEvents,
            OutboxEventStore outbox,
            SlackInboundWorkerProperties properties) {
        this.inboundEvents = inboundEvents;
        this.outbox = outbox;
        this.properties = properties;
    }

    /** Persist + reserve broker wake-up + append outbox are one PostgreSQL transaction. */
    @Transactional
    InboundEvent persistAndWake(
            UUID integrationId,
            String externalEventId,
            String payloadJson,
            String correlationId) {
        InboundEvent event = inboundEvents.persistAuthenticated(
                "SLACK", integrationId, externalEventId, payloadJson, correlationId);
        if (inboundEvents.reserveWake(event.id())) {
            appendWake(event);
        }
        return inboundEvents.findById(event.id()).orElseThrow();
    }

    /**
     * Reserves due rows and appends their wake-ups transactionally. A broker outage therefore
     * leaves one pending outbox event and wake_pending=true instead of generating a retry storm.
     */
    @Transactional
    int redispatchDue() {
        List<InboundEvent> reserved = inboundEvents.reserveDueForDispatch("SLACK", properties.batchSize());
        reserved.forEach(this::appendWake);
        return reserved.size();
    }

    private void appendWake(InboundEvent event) {
        outbox.append(
                OUTBOX_TYPE,
                AGGREGATE_TYPE,
                event.id(),
                "{\"inboundEventId\":\"" + event.id() + "\"}",
                event.correlationId());
    }
}
