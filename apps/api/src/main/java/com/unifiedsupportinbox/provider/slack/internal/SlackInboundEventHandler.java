package com.unifiedsupportinbox.provider.slack.internal;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Extension point owned by the Slack provider module. USI-130 supplies the real filtering and
 * normalization handler; USI-129 owns the durable lifecycle around it.
 */
@FunctionalInterface
interface SlackInboundEventHandler {

    void handle(SlackInboundEvent event);

    record SlackInboundEvent(
            UUID inboundEventId,
            UUID integrationId,
            String externalEventId,
            JsonNode callback,
            JsonNode event,
            String correlationId) {
    }
}
