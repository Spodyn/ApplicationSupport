package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.util.UUID;

/** Starts the durable SLA snapshot whenever a new Case is created. */
public interface CaseSlaInitializer {
    void initialize(UUID caseId, Instant createdAt);
}
