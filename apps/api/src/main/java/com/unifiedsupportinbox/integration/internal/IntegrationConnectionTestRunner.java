package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.integration.IntegrationConnectionTester;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
final class IntegrationConnectionTestRunner {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService executor;
    private final Duration timeout;

    IntegrationConnectionTestRunner() {
        this(Executors.newVirtualThreadPerTaskExecutor(), TIMEOUT);
    }

    IntegrationConnectionTestRunner(ExecutorService executor, Duration timeout) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    IntegrationConnectionTester.Result run(
            IntegrationConnectionTester tester,
            IntegrationRecord integration) {
        Future<IntegrationConnectionTester.Result> pending = executor.submit(() -> tester.test(
                new IntegrationConnectionTester.Request(
                        integration.id(),
                        integration.provider(),
                        integration.workspaceExternalId(),
                        integration.secretRef() != null)));
        try {
            IntegrationConnectionTester.Result result = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return result == null || result.outcome() == null
                    ? IntegrationConnectionTester.Result.unavailable()
                    : result;
        } catch (TimeoutException timeoutFailure) {
            pending.cancel(true);
            return IntegrationConnectionTester.Result.timeout();
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return IntegrationConnectionTester.Result.unavailable();
        } catch (ExecutionException | RuntimeException failure) {
            return IntegrationConnectionTester.Result.unavailable();
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
