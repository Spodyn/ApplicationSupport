package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.messaging.MessageDeliveryChanged;
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
        payload.put("type", "message.delivery_updated");
        payload.put("messageId", event.messageId().toString());
        payload.put("caseId", event.caseId().toString());
        payload.put("deliveryStatus", event.deliveryStatus().name());
        if (event.errorCategory() != null) payload.put("errorCategory", event.errorCategory());
        if (event.errorCode() != null) payload.put("errorCode", event.errorCode());
        if (event.nextRetryAt() != null) payload.put("nextRetryAt", event.nextRetryAt().toString());
        try {
            messaging.convertAndSend("/topic/cases/" + event.caseId(), (Object) payload);
        } catch (RuntimeException deliveryFailure) {
            LOGGER.warn(
                    "Realtime delivery update could not be published after commit; messageId={}, caseId={}, status={}",
                    event.messageId(), event.caseId(), event.deliveryStatus(), deliveryFailure);
        }
    }
}
