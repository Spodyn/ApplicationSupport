package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Writes the transport-neutral Message-created domain signal in the same transaction as the Message row. */
@Component
class MessageCreatedOutboxPublisher {

    static final String OUTBOX_TYPE = "message.created";
    private static final String AGGREGATE_TYPE = "message";

    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    MessageCreatedOutboxPublisher(OutboxEventStore outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    void publish(UUID messageId, UUID caseId, String correlationId) {
        publish(OUTBOX_TYPE, messageId, caseId, correlationId);
    }

    void publishUpdated(UUID messageId, UUID caseId, String correlationId) {
        publish("message.updated", messageId, caseId, correlationId);
        // Also refresh lists in clients that are viewing a different conversation.
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        outbox.append("case.updated", "case", caseId, payload.toString(), correlationId);
    }

    private void publish(String type, UUID messageId, UUID caseId, String correlationId) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(caseId, "caseId");
        ObjectNode payload = json.createObjectNode();
        payload.put("messageId", messageId.toString());
        payload.put("caseId", caseId.toString());
        outbox.append(type, AGGREGATE_TYPE, messageId, payload.toString(), correlationId);
    }
}
