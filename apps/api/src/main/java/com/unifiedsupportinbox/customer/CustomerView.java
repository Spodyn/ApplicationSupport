package com.unifiedsupportinbox.customer;

import java.time.Instant;
import java.util.UUID;

/** Stable read projection used by API and future Case DTOs. */
public record CustomerView(
        UUID id,
        String name,
        String externalRef,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {
}
