package com.unifiedsupportinbox.provider.internal;

import com.unifiedsupportinbox.provider.HistoryRecoveryAdapter;
import java.time.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="usi.providers.history-recovery.worker.enabled",havingValue="true",matchIfMissing=true)
class HistoryRecoveryWorker {
    private final HistoryRecoveryStore store;
    private final List<HistoryRecoveryAdapter> adapters;
    HistoryRecoveryWorker(HistoryRecoveryStore store, List<HistoryRecoveryAdapter> adapters) { this.store=store;this.adapters=adapters; }
    @Scheduled(fixedDelayString="${usi.providers.history-recovery.poll-interval:5s}")
    void poll() {
        store.schedule(Instant.now());
        // Each claim performs at most one HTTP page; no network call holds a DB transaction.
        store.claim().ifPresent(this::process);
    }
    void process(HistoryRecoveryAdapter.Task task) {
        try {
            var adapter=adapters.stream().filter(a->a.provider().equals(task.provider())).findFirst().orElseThrow();
            store.commit(task,adapter,adapter.fetch(task));
        } catch(HistoryRecoveryAdapter.Unavailable failure) {
            store.fail(task,failure.code,failure.retryAfter);
        } catch(RuntimeException failure) {
            store.fail(task,"RECOVERY_TEMPORARILY_UNAVAILABLE",Duration.ofSeconds(30));
        }
    }
}
