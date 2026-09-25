package com.unifiedsupportinbox.ooo;

import java.time.Instant;

/** Versioned administrator-facing out-of-office policy and its safe preview. */
public record OutOfOfficePolicyView(
        boolean enabled,
        String template,
        boolean sendOncePerClosure,
        long version,
        Instant updatedAt,
        String updatedBy) {
}
