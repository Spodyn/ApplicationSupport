package com.unifiedsupportinbox.cases;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Instant;
import java.util.UUID;

/**
 * Provider-neutral application boundary for creating the first Case for an inbound conversation.
 */
public interface CaseCreationService {

    Result create(Command command);

    record Command(
            UUID sourceInboundEventId,
            UUID integrationId,
            UUID channelId,
            IntegrationProvider provider,
            String externalConversationId,
            String externalThreadKey,
            String correlationId) {
    }

    record Result(
            UUID caseId,
            String reference,
            UUID customerId,
            UUID integrationId,
            UUID channelId,
            IntegrationProvider provider,
            String externalConversationId,
            String externalThreadKey,
            CaseStatus status,
            Instant createdAt,
            boolean created) {
    }
}
