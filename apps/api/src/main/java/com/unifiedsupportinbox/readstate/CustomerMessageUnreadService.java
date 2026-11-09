package com.unifiedsupportinbox.readstate;

import java.util.UUID;

/**
 * Provider-neutral boundary for applying personal unread semantics after a new inbound customer
 * Message has been persisted.
 */
public interface CustomerMessageUnreadService {

    void customerMessageEdited(UUID caseId, UUID messageId, String correlationId);

    void customerMessageCreated(UUID caseId, UUID messageId, String correlationId);
}
