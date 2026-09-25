package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.util.UUID;

/** Records the one-time end of unclaimed SLA aging when a Case receives an owner. */
public interface CaseSlaClaimRecorder {
    void recordClaim(UUID caseId, Instant claimedAt);
}
