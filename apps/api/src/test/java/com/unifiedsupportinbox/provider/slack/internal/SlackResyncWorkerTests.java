package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SlackResyncWorkerTests {

    private SlackResyncRepository jobs;
    private ProviderIntegrationCredentialLookup integrations;
    private ConfiguredProviderSecretResolver secrets;
    private SlackHistoryClient slack;
    private SlackInboundDeliveryService inbound;
    private ObjectMapper json;
    private SlackResyncWorker worker;
    private UUID integrationId;
    private UUID channelId;

    @BeforeEach
    void setUp() {
        jobs = mock(SlackResyncRepository.class);
        integrations = mock(ProviderIntegrationCredentialLookup.class);
        secrets = mock(ConfiguredProviderSecretResolver.class);
        slack = mock(SlackHistoryClient.class);
        inbound = mock(SlackInboundDeliveryService.class);
        json = new ObjectMapper();
        worker = new SlackResyncWorker(jobs, integrations, secrets, slack, inbound, json);
        integrationId = UUID.randomUUID();
        channelId = UUID.randomUUID();
        when(integrations.findForProvider(IntegrationProvider.SLACK)).thenReturn(List.of(
                new CredentialReference(integrationId, "T123", "slack/test-workspace")));
    }

    @Test
    void schedulesRootAndThreadReplyThroughTheNormalInboundPipeline() {
        byte[] token = credential();
        when(secrets.resolve("slack/test-workspace", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        ObjectNode root = message("1712000000.000001", "root");
        root.put("reply_count", 1);
        ObjectNode reply = message("1712000001.000002", "reply");
        reply.put("thread_ts", "1712000000.000001");
        when(slack.history(any(), eq("C123"), isNull(), anyString(), anyString(), eq(15)))
                .thenReturn(page(List.of(root), null));
        when(slack.replies(any(), eq("C123"), eq("1712000000.000001"), isNull(), anyString(), anyString(), anyInt()))
                .thenReturn(page(List.of(root, reply), null));

        SlackResyncRepository.Job job = job(50);
        worker.process(job);

        ArgumentCaptor<String> eventIds = ArgumentCaptor.forClass(String.class);
        verify(inbound, times(2)).persistAndWake(
                eq(integrationId),
                eventIds.capture(),
                anyString(),
                eq("slack-resync:" + job.id()));
        assertThat(eventIds.getAllValues()).doesNotHaveDuplicates().allMatch(value -> value.startsWith("resync:"));
        verify(jobs).advance(job.id(), null, 2, 2, true);
        assertThat(token).containsOnly((byte) 0);
    }

    @Test
    void retryAfterDefersWithoutAdvancingTheCheckpointOrSchedulingInboundEvents() {
        byte[] token = credential();
        when(secrets.resolve("slack/test-workspace", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(slack.history(any(), eq("C123"), isNull(), anyString(), anyString(), eq(15)))
                .thenReturn(new SlackHistoryClient.Page(
                        429, false, List.of(), null, "rate_limited", Duration.ofSeconds(73)));
        SlackResyncRepository.Job job = job(100);

        worker.process(job);

        verify(jobs).defer(job.id(), "SLACK_RATE_LIMITED", Duration.ofSeconds(73));
        verify(jobs, never()).advance(any(), any(), anyInt(), anyInt(), any(Boolean.class));
        verify(inbound, never()).persistAndWake(any(), anyString(), anyString(), anyString());
        assertThat(token).containsOnly((byte) 0);
    }

    @Test
    void maxMessagesIsAHardBoundEvenWhenProviderReturnsMoreItems() {
        byte[] token = credential();
        when(secrets.resolve("slack/test-workspace", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(token));
        when(slack.history(any(), eq("C123"), isNull(), anyString(), anyString(), eq(2)))
                .thenReturn(page(List.of(
                        message("1712000000.000001", "one"),
                        message("1712000001.000002", "two"),
                        message("1712000002.000003", "three")), "provider-has-more"));
        SlackResyncRepository.Job job = job(2);

        worker.process(job);

        verify(inbound, times(2)).persistAndWake(eq(integrationId), anyString(), anyString(), anyString());
        verify(jobs).advance(job.id(), null, 2, 2, true);
    }

    @Test
    void recoveryEventIdIsStableWhenAPageIsRepeatedAfterARestart() {
        byte[] firstToken = credential();
        byte[] secondToken = credential();
        when(secrets.resolve("slack/test-workspace", SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE))
                .thenReturn(Optional.of(firstToken), Optional.of(secondToken));
        ObjectNode root = message("1712000000.000001", "same message");
        when(slack.history(any(), eq("C123"), isNull(), anyString(), anyString(), anyInt()))
                .thenReturn(page(List.of(root), null));
        SlackResyncRepository.Job firstClaim = job(50);
        SlackResyncRepository.Job recoveredClaim = jobWithId(firstClaim.id(), 50);

        worker.process(firstClaim);
        worker.process(recoveredClaim);

        ArgumentCaptor<String> eventIds = ArgumentCaptor.forClass(String.class);
        verify(inbound, times(2)).persistAndWake(eq(integrationId), eventIds.capture(), anyString(), anyString());
        assertThat(eventIds.getAllValues()).containsOnly(eventIds.getAllValues().getFirst());
    }

    private SlackResyncRepository.Job job(int maxMessages) {
        return jobWithId(UUID.randomUUID(), maxMessages);
    }

    private SlackResyncRepository.Job jobWithId(UUID id, int maxMessages) {
        Instant now = Instant.parse("2026-09-27T17:00:00Z");
        return new SlackResyncRepository.Job(
                id,
                integrationId,
                channelId,
                "C123",
                "RUNNING",
                "1758900000.000000000",
                "1758990000.000000000",
                null,
                0,
                0,
                maxMessages,
                now,
                null,
                now.minusSeconds(10),
                now,
                null);
    }

    private SlackHistoryClient.Page page(List<tools.jackson.databind.JsonNode> messages, String nextCursor) {
        return new SlackHistoryClient.Page(200, true, messages, nextCursor, null, null);
    }

    private ObjectNode message(String ts, String text) {
        ObjectNode message = json.createObjectNode();
        message.put("type", "message");
        message.put("ts", ts);
        message.put("user", "U123");
        message.put("text", text);
        return message;
    }

    private static byte[] credential() {
        return "fixture-resync-credential".getBytes(StandardCharsets.US_ASCII);
    }
}
