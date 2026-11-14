package com.unifiedsupportinbox.provider.slack.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "usi.slack.inbound-worker", name = "enabled", havingValue = "true")
class SlackInboundAttachmentWorker {

    private final SlackInboundAttachmentService attachments;

    SlackInboundAttachmentWorker(SlackInboundAttachmentService attachments) {
        this.attachments = attachments;
    }

    @Scheduled(fixedDelayString = "${usi.slack.inbound-worker.poll-interval:1s}")
    void processNext() {
        attachments.processNext();
    }
}
