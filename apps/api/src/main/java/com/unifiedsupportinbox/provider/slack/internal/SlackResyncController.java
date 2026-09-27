package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.provider.slack.internal.SlackResyncService.SlackResyncView;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/integrations/{integrationId}/slack/resync")
class SlackResyncController {

    private final SlackResyncService resync;

    SlackResyncController(SlackResyncService resync) {
        this.resync = resync;
    }

    @PostMapping
    ResponseEntity<SlackResyncView> start(
            @PathVariable UUID integrationId,
            @RequestBody StartSlackResyncRequest request) {
        SlackResyncView job = resync.start(
                integrationId,
                request.channelId(),
                request.lookbackHours(),
                request.maxMessages());
        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/{jobId}")
    SlackResyncView get(
            @PathVariable UUID integrationId,
            @PathVariable UUID jobId) {
        return resync.get(integrationId, jobId);
    }

    record StartSlackResyncRequest(
            UUID channelId,
            Integer lookbackHours,
            Integer maxMessages) {

        StartSlackResyncRequest {
            if (channelId == null) {
                throw com.unifiedsupportinbox.ApiProblemException.validationFailed("channelId is required.");
            }
        }
    }
}
