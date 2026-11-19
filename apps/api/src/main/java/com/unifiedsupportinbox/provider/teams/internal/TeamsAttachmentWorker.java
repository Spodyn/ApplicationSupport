package com.unifiedsupportinbox.provider.teams.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="usi.teams.inbound-worker.enabled",havingValue="true")
class TeamsAttachmentWorker {
    private final TeamsInboundAttachments attachments;
    TeamsAttachmentWorker(TeamsInboundAttachments attachments){this.attachments=attachments;}
    @Scheduled(fixedDelayString="${usi.teams.attachments.poll-interval:1s}")
    void poll(){attachments.processNext();}
}
