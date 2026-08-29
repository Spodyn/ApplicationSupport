package com.unifiedsupportinbox.provider.telegram.internal;

import com.unifiedsupportinbox.InboundEventStore;
import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.OutboxEventStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TelegramInboundDeliveryService {
    static final String OUTBOX_TYPE = "telegram.inbound.received";
    private final InboundEventStore inboundEvents;
    private final OutboxEventStore outbox;
    TelegramInboundDeliveryService(InboundEventStore inboundEvents, OutboxEventStore outbox) {
        this.inboundEvents = inboundEvents;
        this.outbox = outbox;
    }

    @Transactional
    InboundEvent persistAndWake(UUID integrationId, String updateId, String payloadJson, String correlationId) {
        InboundEvent event = inboundEvents.persistAuthenticated(
                "TELEGRAM", integrationId, updateId, payloadJson, correlationId);
        if (inboundEvents.reserveWake(event.id())) {
            outbox.append(
                    OUTBOX_TYPE,
                    "inbound_event",
                    event.id(),
                    "{\"inboundEventId\":\"" + event.id() + "\"}",
                    event.correlationId());
        }
        return inboundEvents.findById(event.id()).orElseThrow();
    }
}
