package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.util.UUID;

/** Applies the policy-aware initial SLA pause when Ask Customer enters WAITING. */
public interface CaseSlaWaitingRecorder {
    void pauseForWaiting(UUID caseId, Instant pausedAt);
    void resumeAfterWaiting(UUID caseId, Instant resumedAt);
}
