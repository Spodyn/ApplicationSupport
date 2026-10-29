package com.unifiedsupportinbox.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "usi.cases.waiting-timeout.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
class WaitingTimeoutScheduler {

    private static final int BATCH_SIZE = 100;
    private final WaitingTimeoutService timeouts;

    WaitingTimeoutScheduler(WaitingTimeoutService timeouts) {
        this.timeouts = timeouts;
    }

    @Scheduled(fixedDelayString = "${usi.cases.waiting-timeout.poll-interval:5s}")
    void poll() {
        timeouts.processDue(BATCH_SIZE);
    }
}
