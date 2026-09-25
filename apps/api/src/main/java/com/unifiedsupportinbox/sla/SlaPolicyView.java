package com.unifiedsupportinbox.sla;

import java.time.Instant;
import java.util.UUID;

/** Active organization SLA policy, expressed in minutes for the administration contract. */
public record SlaPolicyView(UUID id, String name, long firstResponseMinutes, long unclaimedWarningMinutes,
        long unclaimedBreachMinutes, long inProgressWarningMinutes, long inProgressBreachMinutes,
        boolean pauseWaiting, long version, Instant updatedAt) { }
