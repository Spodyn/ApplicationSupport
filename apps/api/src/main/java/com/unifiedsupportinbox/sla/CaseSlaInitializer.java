package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.util.UUID;

/** Starts the durable SLA snapshot whenever a new Case is created. */
public interface CaseSlaInitializer {
    default void initialize(UUID caseId, Instant createdAt) {
        initialize(caseId, createdAt, createdAt);
    }

    void initialize(UUID caseId, Instant createdAt, Instant customerSentAt);
}
