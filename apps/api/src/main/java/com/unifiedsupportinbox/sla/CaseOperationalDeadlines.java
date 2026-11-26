package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.util.UUID;

/** Writes are serialized by the owning Case row lock. */
public interface CaseOperationalDeadlines {
    int customerWaitingMinutes();
    void followUp(UUID caseId, UUID messageId, Instant acceptedAt, Instant dueAt);
    void finish(UUID caseId, Instant at, boolean successfulResponse);
}
