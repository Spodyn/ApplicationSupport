package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class SlackResyncWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlackResyncWorker.class);
    private static final Duration RUNNING_LEASE = Duration.ofMinutes(2);
    private static final Duration DEFAULT_TRANSIENT_RETRY = Duration.ofSeconds(30);
    private static final Duration DEFAULT_RATE_LIMIT_RETRY = Duration.ofMinutes(1);
    private static final int PROVIDER_PAGE_SIZE = 15;

    private final SlackResyncRepository jobs;
    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final SlackHistoryClient slack;
    private final SlackInboundDeliveryService inbound;
    private final ObjectMapper json;

    SlackResyncWorker(
            SlackResyncRepository jobs,
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,
            SlackHistoryClient slack,
            SlackInboundDeliveryService inbound,
            ObjectMapper json) {
        this.jobs = jobs;
        this.integrations = integrations;
        this.secrets = secrets;
        this.slack = slack;
        this.inbound = inbound;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${usi.providers.slack.resync.poll-interval:5s}")
    void poll() {
        jobs.claimDue(RUNNING_LEASE).ifPresent(this::process);
    }

    void process(SlackResyncRepository.Job job) {
        if (job.remaining() <= 0) {
            jobs.advance(job.id(), null, 0, 0, true);
            return;
        }

        CredentialReference credential = credentialFor(job.integrationId());
        if (credential == null) {
            jobs.fail(job.id(), "SLACK_CREDENTIAL_REFERENCE_MISSING");
            return;
        }
        byte[] token = secrets.resolve(
                        credential.secretRef(),
                        SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE)
                .orElse(null);
        if (token == null) {
            jobs.fail(job.id(), "SLACK_BOT_TOKEN_MISSING");
            return;
        }

        try {
            int limit = Math.min(PROVIDER_PAGE_SIZE, job.remaining());
            SlackHistoryClient.Page page = requestHistory(job, token, limit);
            if (page == null || !accept(job, page)) return;

            Progress progress = new Progress(job.remaining());
            try {
                for (JsonNode message : page.messages()) {
                    if (progress.full()) break;
                    String rootTs = messageTs(message);
                    progress.fetched++;
                    if (rootTs != null && enqueue(job, message, rootTs)) progress.scheduled++;

                    if (rootTs != null && replyCount(message) > 0 && !progress.full()) {
                        ReplyResult replies = enqueueReplies(job, token, rootTs, progress);
                        if (replies == ReplyResult.DEFERRED_OR_FAILED) return;
                    }
                }
            } catch (EnqueueFailure failure) {
                LOGGER.warn(
                        "Slack resync could not persist recovered inbound data; jobId={}, integrationId={}",
                        job.id(),
                        job.integrationId());
                jobs.defer(job.id(), "SLACK_RESYNC_PERSIST_FAILED", DEFAULT_TRANSIENT_RETRY);
                return;
            }

            boolean complete = progress.full() || blank(page.nextCursor());
            jobs.advance(
                    job.id(),
                    complete ? null : page.nextCursor(),
                    progress.fetched,
                    progress.scheduled,
                    complete);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private SlackHistoryClient.Page requestHistory(
            SlackResyncRepository.Job job,
            byte[] token,
            int limit) {
        try {
            return slack.history(
                    token,
                    job.externalChannelId(),
                    job.cursor(),
                    job.oldestTs(),
                    job.latestTs(),
                    limit);
        } catch (RuntimeException providerFailure) {
            LOGGER.warn(
                    "Slack history request failed; jobId={}, integrationId={}",
                    job.id(),
                    job.integrationId());
            jobs.defer(job.id(), "SLACK_RESYNC_PROVIDER_UNAVAILABLE", DEFAULT_TRANSIENT_RETRY);
            return null;
        }
    }

    private SlackHistoryClient.Page requestReplies(
            SlackResyncRepository.Job job,
            byte[] token,
            String rootTs,
            String cursor,
            int limit) {
        try {
            return slack.replies(
                    token,
                    job.externalChannelId(),
                    rootTs,
                    cursor,
                    job.oldestTs(),
                    job.latestTs(),
                    limit);
        } catch (RuntimeException providerFailure) {
            LOGGER.warn(
                    "Slack thread recovery request failed; jobId={}, integrationId={}",
                    job.id(),
                    job.integrationId());
            jobs.defer(job.id(), "SLACK_RESYNC_PROVIDER_UNAVAILABLE", DEFAULT_TRANSIENT_RETRY);
            return null;
        }
    }

    private ReplyResult enqueueReplies(
            SlackResyncRepository.Job job,
            byte[] token,
            String rootTs,
            Progress progress) {
        String cursor = null;
        do {
            int limit = Math.min(PROVIDER_PAGE_SIZE, progress.remaining());
            if (limit <= 0) return ReplyResult.COMPLETE;
            SlackHistoryClient.Page page = requestReplies(job, token, rootTs, cursor, limit);
            if (page == null || !accept(job, page)) return ReplyResult.DEFERRED_OR_FAILED;

            for (JsonNode reply : page.messages()) {
                if (progress.full()) return ReplyResult.COMPLETE;
                String replyTs = messageTs(reply);
                if (rootTs.equals(replyTs)) continue;
                progress.fetched++;
                if (replyTs != null && enqueue(job, reply, replyTs)) progress.scheduled++;
            }
            cursor = page.nextCursor();
        } while (!blank(cursor) && !progress.full());
        return ReplyResult.COMPLETE;
    }

    private boolean enqueue(SlackResyncRepository.Job job, JsonNode source, String messageTs) {
        if (source == null || !source.isObject()) return false;
        ObjectNode event = ((ObjectNode) source).deepCopy();
        event.put("channel", job.externalChannelId());

        String externalEventId = recoveryEventId(job.externalChannelId(), messageTs);
        ObjectNode callback = json.createObjectNode();
        callback.put("type", "event_callback");
        callback.put("event_id", externalEventId);
        callback.set("event", event);
        try {
            inbound.persistAndWake(
                    job.integrationId(),
                    externalEventId,
                    json.writeValueAsString(callback),
                    "slack-resync:" + job.id());
            return true;
        } catch (JacksonException exception) {
            throw new EnqueueFailure(exception);
        } catch (RuntimeException exception) {
            throw new EnqueueFailure(exception);
        }
    }

    private boolean accept(SlackResyncRepository.Job job, SlackHistoryClient.Page page) {
        if (page == null) return false;
        if (page.statusCode() == 429) {
            jobs.defer(job.id(), "SLACK_RATE_LIMITED", positive(page.retryAfter(), DEFAULT_RATE_LIMIT_RETRY));
            return false;
        }
        if (page.statusCode() < 200 || page.statusCode() >= 300) {
            String code = "SLACK_HTTP_" + page.statusCode();
            if (SlackDeliveryErrorClassifier.isTransientHttpStatus(page.statusCode())) {
                jobs.defer(job.id(), code, positive(page.retryAfter(), DEFAULT_TRANSIENT_RETRY));
            } else {
                jobs.fail(job.id(), code);
            }
            return false;
        }
        if (!page.ok()) {
            String code = SlackDeliveryErrorClassifier.normalizedApiError(page.errorCode());
            if (SlackDeliveryErrorClassifier.isTransientApiError(page.errorCode())) {
                jobs.defer(job.id(), code, positive(page.retryAfter(), DEFAULT_TRANSIENT_RETRY));
            } else {
                jobs.fail(job.id(), code);
            }
            return false;
        }
        return true;
    }

    private CredentialReference credentialFor(UUID integrationId) {
        List<CredentialReference> matches = integrations.findForProvider(IntegrationProvider.SLACK).stream()
                .filter(reference -> integrationId.equals(reference.integrationId()))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String recoveryEventId(String channelId, String messageTs) {
        UUID stable = UUID.nameUUIDFromBytes(
                (channelId + ":" + messageTs).getBytes(StandardCharsets.UTF_8));
        return "resync:" + stable;
    }

    private static String messageTs(JsonNode message) {
        if (message == null) return null;
        JsonNode ts = message.get("ts");
        return ts != null && ts.isTextual() && !ts.stringValue().isBlank()
                ? ts.stringValue()
                : null;
    }

    private static int replyCount(JsonNode message) {
        JsonNode count = message == null ? null : message.get("reply_count");
        return count == null ? 0 : Math.max(0, count.asInt(0));
    }

    private static Duration positive(Duration requested, Duration fallback) {
        return requested != null && !requested.isZero() && !requested.isNegative()
                ? requested
                : fallback;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private enum ReplyResult {
        COMPLETE,
        DEFERRED_OR_FAILED
    }

    private static final class EnqueueFailure extends RuntimeException {
        private EnqueueFailure(Throwable cause) {
            super(cause);
        }
    }

    private static final class Progress {
        private final int maximum;
        private int fetched;
        private int scheduled;

        private Progress(int maximum) {
            this.maximum = maximum;
        }

        private int remaining() {
            return Math.max(0, maximum - fetched);
        }

        private boolean full() {
            return fetched >= maximum;
        }
    }
}
