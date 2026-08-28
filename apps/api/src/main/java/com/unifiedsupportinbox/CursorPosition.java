package com.unifiedsupportinbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable pagination tuple: an indexed sort value plus UUID id as a deterministic
 * tie-breaker. Repository queries must apply the equivalent tuple predicate.
 */
public record CursorPosition(Instant sortValue, UUID id) {

    public CursorPosition {
        Objects.requireNonNull(sortValue, "sortValue");
        Objects.requireNonNull(id, "id");
    }

    static int compareNatural(CursorPosition left, CursorPosition right) {
        int timestamp = left.sortValue.compareTo(right.sortValue);
        if (timestamp != 0) {
            return timestamp;
        }
        int mostSignificant = Long.compareUnsigned(
                left.id.getMostSignificantBits(), right.id.getMostSignificantBits());
        if (mostSignificant != 0) {
            return mostSignificant;
        }
        return Long.compareUnsigned(
                left.id.getLeastSignificantBits(), right.id.getLeastSignificantBits());
    }
}
