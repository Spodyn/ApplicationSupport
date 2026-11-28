package com.unifiedsupportinbox.messaging;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Instant;
import java.util.UUID;

/** Called only after the provider's existing authentication, context and inbox checks. */
public interface OutboundMutationCallbacks {
    boolean observe(IntegrationProvider provider, UUID integrationId, String externalChannelId, String providerConversationId,
                 String providerMessageRef, String senderId, String body, boolean deleted, Instant occurredAt);
}
