package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.InboundEventStore;
import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.integration.IntegrationHealthReporter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SlackInboundDeliveryServiceTests {

    @Test
    void authenticatedDurableReceiptRefreshesIntegrationHealthBeforeWorkerProcessing() {
        InboundEventStore events = mock(InboundEventStore.class);
        OutboxEventStore outbox = mock(OutboxEventStore.class);
        SlackInboundWorkerProperties properties = mock(SlackInboundWorkerProperties.class);
        IntegrationHealthReporter health = mock(IntegrationHealthReporter.class);
        SlackInboundDeliveryService service = new SlackInboundDeliveryService(events, outbox, properties, health);

        UUID integrationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        InboundEvent event = new InboundEvent(
                eventId,
                "SLACK",
                integrationId,
                "Ev-health",
                "{\"type\":\"event_callback\"}",
                "RECEIVED",
                Instant.now(),
                null,
                null,
                0,
                "corr-health",
                null,
                Instant.now(),
                false,
                null);
        when(events.persistAuthenticated(
                "SLACK", integrationId, "Ev-health", event.payloadJson(), "corr-health"))
                .thenReturn(event);
        when(events.reserveWake(eventId)).thenReturn(false);
        when(events.findById(eventId)).thenReturn(Optional.of(event));

        InboundEvent persisted = service.persistAndWake(
                integrationId, "Ev-health", event.payloadJson(), "corr-health");

        assertThat(persisted).isEqualTo(event);
        verify(health).providerEventReceived(org.mockito.ArgumentMatchers.eq(integrationId), any(Instant.class));
    }
}
