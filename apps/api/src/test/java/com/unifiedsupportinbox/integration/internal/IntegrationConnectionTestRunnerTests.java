package com.unifiedsupportinbox.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.integration.IntegrationConnectionTester;
import com.unifiedsupportinbox.integration.IntegrationHealth;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.IntegrationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IntegrationConnectionTestRunnerTests {

    @Test
    void defaultsToFrozenTenSecondTimeout() {
        assertThat(IntegrationConnectionTestRunner.TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void interruptsProviderTestAndReturnsTimeoutAtTheCommonBoundary() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        IntegrationConnectionTester tester = new IntegrationConnectionTester() {
            @Override
            public boolean supports(IntegrationProvider provider) {
                return true;
            }

            @Override
            public Result test(Request request) {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                    return Result.success();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    return Result.unavailable();
                }
            }
        };
        IntegrationConnectionTestRunner runner = new IntegrationConnectionTestRunner(
                Executors.newVirtualThreadPerTaskExecutor(), Duration.ofMillis(25));

        try {
            IntegrationConnectionTester.Result result = runner.run(tester, integration());

            assertThat(result).isEqualTo(IntegrationConnectionTester.Result.timeout());
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.stop();
        }
    }

    private static IntegrationRecord integration() {
        Instant now = Instant.parse("2026-10-13T07:00:00Z");
        return new IntegrationRecord(
                UUID.randomUUID(),
                IntegrationProvider.SLACK,
                "Test Slack",
                IntegrationStatus.ENABLED,
                IntegrationHealth.UNKNOWN,
                "T-test",
                "Test workspace",
                "test-secret-ref",
                "{}",
                null,
                null,
                now,
                now);
    }
}
