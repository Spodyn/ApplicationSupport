package com.unifiedsupportinbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class IdempotencyCleanup {

    private static final long ONE_HOUR_MILLIS = 3_600_000L;

    private final IdempotencyStore store;

    IdempotencyCleanup(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelay = ONE_HOUR_MILLIS, initialDelay = ONE_HOUR_MILLIS)
    void scheduledCleanup() {
        store.deleteExpired();
    }

    int cleanupExpiredNow() {
        return store.deleteExpired();
    }
}
