package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.ApiProblemException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class SlackResyncService {

    static final int DEFAULT_LOOKBACK_HOURS = 24;
    static final int MAX_LOOKBACK_HOURS = 168;
    static final int DEFAULT_MAX_MESSAGES = 200;
    static final int ABSOLUTE_MAX_MESSAGES = 500;

    private final SlackResyncRepository jobs;

    SlackResyncService(SlackResyncRepository jobs) {
        this.jobs = jobs;
    }

    SlackResyncView start(
            UUID integrationId,
            UUID channelId,
            Integer requestedLookbackHours,
            Integer requestedMaxMessages) {
        int lookbackHours = requestedLookbackHours == null
                ? DEFAULT_LOOKBACK_HOURS
                : requestedLookbackHours;
        int maxMessages = requestedMaxMessages == null
                ? DEFAULT_MAX_MESSAGES
                : requestedMaxMessages;
        if (lookbackHours < 1 || lookbackHours > MAX_LOOKBACK_HOURS) {
            throw ApiProblemException.validationFailed("lookbackHours must be between 1 and 168.");
        }
        if (maxMessages < 1 || maxMessages > ABSOLUTE_MAX_MESSAGES) {
            throw ApiProblemException.validationFailed("maxMessages must be between 1 and 500.");
        }

        Instant latest = Instant.now();
        Instant oldest = latest.minus(lookbackHours, ChronoUnit.HOURS);
        return view(jobs.create(
                integrationId,
                channelId,
                slackTimestamp(oldest),
                slackTimestamp(latest),
                maxMessages));
    }

    SlackResyncView get(UUID integrationId, UUID jobId) {
        return jobs.find(integrationId, jobId)
                .map(SlackResyncService::view)
                .orElseThrow(() -> ApiProblemException.notFound("Slack resync job was not found."));
    }

    private static SlackResyncView view(SlackResyncRepository.Job job) {
        return new SlackResyncView(
                job.id(),
                job.integrationId(),
                job.channelId(),
                job.status(),
                job.fetchedMessages(),
                job.scheduledEvents(),
                job.maxMessages(),
                job.nextAttemptAt(),
                job.lastErrorCode(),
                job.createdAt(),
                job.updatedAt(),
                job.completedAt());
    }

    private static String slackTimestamp(Instant instant) {
        return String.format(
                Locale.ROOT,
                "%d.%09d",
                instant.getEpochSecond(),
                instant.getNano());
    }

    record SlackResyncView(
            UUID id,
            UUID integrationId,
            UUID channelId,
            String status,
            int fetchedMessages,
            int scheduledEvents,
            int maxMessages,
            Instant nextAttemptAt,
            String lastErrorCode,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {
    }
}
