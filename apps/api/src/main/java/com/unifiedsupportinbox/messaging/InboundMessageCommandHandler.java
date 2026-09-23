package com.unifiedsupportinbox.messaging;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Instant;
import java.util.UUID;

/**
 * Provider-neutral boundary between inbound provider adapters and the Case/Message domain.
 *
 * <p>Provider adapters own authentication and payload normalization. Downstream business logic
 * owns case grouping, message persistence, unread/SLA semantics and workflow effects.</p>
 */
@FunctionalInterface
public interface InboundMessageCommandHandler {

    void handle(Command command);

    enum Mutation {
        CREATE,
        EDIT,
        DELETE
    }

    record Command(
            UUID inboundEventId,
            UUID integrationId,
            UUID channelId,
            IntegrationProvider provider,
            String externalEventId,
            String externalChannelId,
            String externalMessageId,
            String externalThreadKey,
            String authorExternalId,
            String body,
            Mutation mutation,
            Instant providerOccurredAt,
            String correlationId) {
    }
}
