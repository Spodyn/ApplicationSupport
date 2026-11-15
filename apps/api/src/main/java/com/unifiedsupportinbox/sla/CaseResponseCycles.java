package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.util.UUID;

/** Durable customer response obligations, updated inside the owning Case transaction. */
public interface CaseResponseCycles {
    Instant lockForDelivery(UUID caseId);

    void customerMessage(UUID caseId, UUID messageId, Instant receivedAt);

    void humanResponse(UUID caseId, UUID messageId, Instant acceptedAt);

    void systemResponse(UUID caseId);

    void close(UUID caseId);
}
