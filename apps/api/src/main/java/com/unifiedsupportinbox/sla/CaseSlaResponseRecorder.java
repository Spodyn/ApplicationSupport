package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.util.UUID;

/** Completes first-response SLA only after a provider accepts an outbound support message. */
public interface CaseSlaResponseRecorder {
    void recordProviderAcceptedResponse(UUID caseId, Instant sentAt);
}
