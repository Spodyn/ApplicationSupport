package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.messaging.MessageDeliveryChanged;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class MessageDeliveryRealtimeBridge {

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
        messaging.convertAndSend("/topic/cases/" + event.caseId(), (Object) payload);
    }
}
