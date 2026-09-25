package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.messaging.MessageDeliveryChanged;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class MessageDeliveryRealtimeBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageDeliveryRealtimeBridge.class);

    private final SimpMessagingTemplate messaging;

    MessageDeliveryRealtimeBridge(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onDeliveryChanged(MessageDeliveryChanged event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deliveryStatus", event.deliveryStatus().name());
        payload.put("caseId", event.caseId().toString());
        if (event.errorCategory() != null) payload.put("errorCategory", event.errorCategory());
        if (event.errorCode() != null) payload.put("errorCode", event.errorCode());
        if (event.nextRetryAt() != null) payload.put("nextRetryAt", event.nextRetryAt().toString());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", "message.delivery_updated");
        envelope.put("version", 1);
        envelope.put("entityId", event.messageId().toString());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("correlationId", event.correlationId());
        envelope.put("payload", payload);
        try {
            messaging.convertAndSend("/topic/cases/" + event.caseId(), (Object) envelope);
        } catch (RuntimeException deliveryFailure) {
            LOGGER.warn(
                    "Realtime delivery update could not be published after commit; messageId={}, caseId={}, status={}",
                    event.messageId(), event.caseId(), event.deliveryStatus(), deliveryFailure);
        }
    }
}
