package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Command;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Mutation;
import com.unifiedsupportinbox.provider.slack.internal.SlackInboundEventHandler.SlackInboundEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SlackNormalizerTests {

    private final ObjectMapper json = new ObjectMapper();
    private final SlackNormalizer normalizer = new SlackNormalizer();
    private final UUID inboundEventId = UUID.randomUUID();
    private final UUID integrationId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();

    @Test
    void normalizesHumanRootMessage() throws Exception {
        SlackInboundEvent inbound = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-root",
                  "event":{"type":"message","channel":"C-support","user":"U-customer","text":"hello","ts":"1720000000.123456"}
                }
                """);

        SlackNormalizer.Accepted accepted = (SlackNormalizer.Accepted) normalizer.normalize(inbound, channelId);
        Command command = accepted.command();

        assertThat(command.provider()).isEqualTo(IntegrationProvider.SLACK);
        assertThat(command.mutation()).isEqualTo(Mutation.CREATE);
        assertThat(command.channelId()).isEqualTo(channelId);
        assertThat(command.externalChannelId()).isEqualTo("C-support");
        assertThat(command.externalMessageId()).isEqualTo("1720000000.123456");
        assertThat(command.externalThreadKey()).isEqualTo("1720000000.123456");
        assertThat(command.authorExternalId()).isEqualTo("U-customer");
        assertThat(command.body()).isEqualTo("hello");
        assertThat(command.providerOccurredAt()).isEqualTo(Instant.ofEpochSecond(1_720_000_000L, 123_456_000));
    }

    @Test
    void normalizesThreadReplyUsingRootThreadIdentity() throws Exception {
        SlackInboundEvent inbound = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-reply",
                  "event":{"type":"message","channel":"C-support","user":"U-customer","text":"reply","ts":"1720000001.000001","thread_ts":"1720000000.123456"}
                }
                """);

        Command command = ((SlackNormalizer.Accepted) normalizer.normalize(inbound, channelId)).command();

        assertThat(command.mutation()).isEqualTo(Mutation.CREATE);
        assertThat(command.externalMessageId()).isEqualTo("1720000001.000001");
        assertThat(command.externalThreadKey()).isEqualTo("1720000000.123456");
    }

    @Test
    void rejectsBotSubtypeAndOwnAuthorizationUserToPreventReplyLoops() throws Exception {
        SlackInboundEvent botSubtype = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-bot",
                  "event":{"type":"message","subtype":"bot_message","channel":"C-support","bot_id":"B1","text":"bot","ts":"1720000000.1"}
                }
                """);
        SlackInboundEvent ownUser = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-own",
                  "authorizations":[{"user_id":"U-usi-bot"}],
                  "event":{"type":"message","channel":"C-support","user":"U-usi-bot","text":"our reply","ts":"1720000000.2"}
                }
                """);

        assertThat(((SlackNormalizer.Filtered) normalizer.normalize(botSubtype, channelId)).reason())
                .isEqualTo(SlackNormalizer.FilterReason.BOT_OR_APP_MESSAGE);
        assertThat(((SlackNormalizer.Filtered) normalizer.normalize(ownUser, channelId)).reason())
                .isEqualTo(SlackNormalizer.FilterReason.BOT_OR_APP_MESSAGE);
    }

    @Test
    void normalizesMessageChangedAsEditWithoutChangingConversationIdentity() throws Exception {
        SlackInboundEvent inbound = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-edit",
                  "event":{
                    "type":"message","subtype":"message_changed","channel":"C-support","event_ts":"1720000010.5",
                    "message":{"type":"message","user":"U-customer","text":"edited","ts":"1720000001.000001","thread_ts":"1720000000.123456","edited":{"user":"U-customer","ts":"1720000010.250000"}}
                  }
                }
                """);

        Command command = ((SlackNormalizer.Accepted) normalizer.normalize(inbound, channelId)).command();

        assertThat(command.mutation()).isEqualTo(Mutation.EDIT);
        assertThat(command.externalMessageId()).isEqualTo("1720000001.000001");
        assertThat(command.externalThreadKey()).isEqualTo("1720000000.123456");
        assertThat(command.body()).isEqualTo("edited");
        assertThat(command.providerOccurredAt()).isEqualTo(Instant.ofEpochSecond(1_720_000_010L, 250_000_000));
    }

    @Test
    void normalizesMessageDeletedAsDeleteWithoutBody() throws Exception {
        SlackInboundEvent inbound = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-delete",
                  "event":{
                    "type":"message","subtype":"message_deleted","channel":"C-support","deleted_ts":"1720000001.000001","event_ts":"1720000020.750000",
                    "previous_message":{"type":"message","user":"U-customer","text":"old","ts":"1720000001.000001","thread_ts":"1720000000.123456"}
                  }
                }
                """);

        Command command = ((SlackNormalizer.Accepted) normalizer.normalize(inbound, channelId)).command();

        assertThat(command.mutation()).isEqualTo(Mutation.DELETE);
        assertThat(command.externalMessageId()).isEqualTo("1720000001.000001");
        assertThat(command.externalThreadKey()).isEqualTo("1720000000.123456");
        assertThat(command.body()).isNull();
        assertThat(command.providerOccurredAt()).isEqualTo(Instant.ofEpochSecond(1_720_000_020L, 750_000_000));
    }

    @Test
    void filtersUnsupportedMessageSubtypeAndNonMessageEvents() throws Exception {
        SlackInboundEvent unsupportedSubtype = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-join",
                  "event":{"type":"message","subtype":"channel_join","channel":"C-support","user":"U1","text":"joined","ts":"1720000000.1"}
                }
                """);
        SlackInboundEvent reaction = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-reaction",
                  "event":{"type":"reaction_added","user":"U1","event_ts":"1720000000.1"}
                }
                """);

        assertThat(((SlackNormalizer.Filtered) normalizer.normalize(unsupportedSubtype, channelId)).reason())
                .isEqualTo(SlackNormalizer.FilterReason.UNSUPPORTED_EVENT);
        assertThat(((SlackNormalizer.Filtered) normalizer.normalize(reaction, channelId)).reason())
                .isEqualTo(SlackNormalizer.FilterReason.UNSUPPORTED_EVENT);
    }

    @Test
    void malformedSupportedMessageIsRejectedInsteadOfSilentlyDropped() throws Exception {
        SlackInboundEvent inbound = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-malformed",
                  "event":{"type":"message","channel":"C-support","user":"U-customer","ts":"1720000000.1"}
                }
                """);

        assertThatThrownBy(() -> normalizer.normalize(inbound, channelId))
                .isInstanceOfSatisfying(SlackInboundProcessingException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(SlackInboundProcessingException.Kind.MALFORMED);
                    assertThat(failure.errorCode()).isEqualTo("MALFORMED_SLACK_MESSAGE");
                });
    }

    private SlackInboundEvent inbound(String payload) throws Exception {
        JsonNode callback = json.readTree(payload);
        return new SlackInboundEvent(
                inboundEventId,
                integrationId,
                callback.get("event_id").stringValue(),
                callback,
                callback.get("event"),
                "corr-test");
    }
}
