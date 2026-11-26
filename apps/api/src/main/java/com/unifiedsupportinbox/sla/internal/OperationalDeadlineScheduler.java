package com.unifiedsupportinbox.sla.internal;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "usi.cases.waiting-timeout.scheduler-enabled", matchIfMissing = true)
record OperationalDeadlineScheduler(OperationalDeadlineService deadlines) {
    @Scheduled(fixedDelayString = "${usi.cases.waiting-timeout.poll-interval:5s}")
    void poll() { deadlines.remindDue(Instant.now()); }
}
