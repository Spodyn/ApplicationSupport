package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface CaseSlaReopenRecorder {
    Map<String, Object> restartUnclaimed(UUID caseId, Instant restartedAt);
}
