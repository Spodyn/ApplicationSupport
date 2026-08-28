package com.unifiedsupportinbox;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class InboundEventProcessor {

    private final InboundEventStore store;
    private final TransactionTemplate transactionTemplate;

    public InboundEventProcessor(InboundEventStore store, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ProcessingResult process(UUID eventId, Handler handler) {
        return process(eventId, "PROCESSING_FAILED", handler);
    }

    /**
     * Runs provider-neutral business processing under the inbound row lock and in the same DB
     * transaction as all business/outbox writes made by the handler.
     */
    public ProcessingResult process(UUID eventId, String failureCode, Handler handler) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(handler, "handler");
        String normalizedFailureCode = normalizeFailureCode(failureCode);

        try {
            ProcessingResult result = transactionTemplate.execute(status -> {
                InboundEvent event = store.lockForProcessing(eventId)
                        .orElseThrow(() -> new IllegalArgumentException("inbound event not found: " + eventId));

                if ("PROCESSED".equals(event.status())) {
                    return ProcessingResult.ALREADY_PROCESSED;
                }
                if (!"RECEIVED".equals(event.status()) && !"FAILED".equals(event.status())) {
                    throw new IllegalStateException(
                            "inbound event is not retryable from status " + event.status());
                }
                if (store.markProcessing(eventId) != 1) {
                    throw new IllegalStateException("failed to claim inbound event " + eventId);
                }

                handler.handle(event);

                if (store.markProcessed(eventId) != 1) {
                    throw new IllegalStateException("failed to mark inbound event processed " + eventId);
                }
                return ProcessingResult.PROCESSED;
            });
            return Objects.requireNonNull(result, "transaction returned no processing result");
        } catch (RuntimeException failure) {
            try {
                store.markFailedAfterRollback(eventId, normalizedFailureCode);
            } catch (RuntimeException failureRecordingError) {
                failure.addSuppressed(failureRecordingError);
            }
            throw failure;
        }
    }

    private static String normalizeFailureCode(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            return "PROCESSING_FAILED";
        }
        String normalized = failureCode
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]+", "_");
        if (normalized.isBlank()) {
            return "PROCESSING_FAILED";
        }
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    public enum ProcessingResult {
        PROCESSED,
        ALREADY_PROCESSED
    }

    @FunctionalInterface
    public interface Handler {
        void handle(InboundEvent event);
    }
}
