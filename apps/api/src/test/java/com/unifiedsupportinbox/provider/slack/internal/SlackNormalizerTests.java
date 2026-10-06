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
                  "event":{"type":"message","channel":"C-support","channel_type":"channel","user":"U-customer","text":"hello","ts":"1720000000.123456"}
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
                  "event":{"type":"message","channel":"C-support","channel_type":"channel","user":"U-customer","text":"reply","ts":"1720000001.000001","thread_ts":"1720000000.123456"}
                }
                """);

        Command command = ((SlackNormalizer.Accepted) normalizer.normalize(inbound, channelId)).command();

        assertThat(command.mutation()).isEqualTo(Mutation.CREATE);
        assertThat(command.externalMessageId()).isEqualTo("1720000001.000001");
        assertThat(command.externalThreadKey()).isEqualTo("1720000000.123456");
    }

    @Test
    void normalizesFileShareMessageSoAttachmentsReachInboundPipeline() throws Exception {
        SlackInboundEvent inbound = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-file",
                  "event":{
                    "type":"message","subtype":"file_share","channel":"C-support","channel_type":"channel","user":"U-customer",
                    "text":"","ts":"1720000002.000001","thread_ts":"1720000000.123456",
                    "files":[{"id":"F123"}]
                  }
                }
                """);

        Command command = ((SlackNormalizer.Accepted) normalizer.normalize(inbound, channelId)).command();

        assertThat(command.mutation()).isEqualTo(Mutation.CREATE);
        assertThat(command.externalMessageId()).isEqualTo("1720000002.000001");
        assertThat(command.externalThreadKey()).isEqualTo("1720000000.123456");
        assertThat(command.body()).isEmpty();
    }

    @Test
    void rejectsBotSubtypeAndOwnAuthorizationUserToPreventReplyLoops() throws Exception {
        SlackInboundEvent botSubtype = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-bot",
                  "event":{"type":"message","subtype":"bot_message","channel":"C-support","channel_type":"channel","bot_id":"B1","text":"bot","ts":"1720000000.1"}
                }
                """);
        SlackInboundEvent ownUser = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-own",
                  "authorizations":[{"user_id":"U-usi-bot"}],
                  "event":{"type":"message","channel":"C-support","channel_type":"channel","user":"U-usi-bot","text":"our reply","ts":"1720000000.2"}
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
                    "type":"message","subtype":"message_changed","channel":"C-support","channel_type":"channel","event_ts":"1720000010.5",
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
                    "type":"message","subtype":"message_deleted","channel":"C-support","channel_type":"channel","deleted_ts":"1720000001.000001","event_ts":"1720000020.750000",
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
    void acceptsPrivateChannelsAndFiltersAllOtherConversationTypes() throws Exception {
        SlackInboundEvent privateChannel = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-private",
                  "event":{"type":"message","channel":"G-support","channel_type":"group","user":"U1","text":"private","ts":"1720000000.3"}
                }
                """);
        SlackInboundEvent directMessage = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-dm",
                  "event":{"type":"message","channel":"D-support","channel_type":"im","user":"U1","text":"dm","ts":"1720000000.1"}
                }
                """);
        SlackInboundEvent groupDirectMessage = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-mpim",
                  "event":{"type":"message","channel":"G-support","channel_type":"mpim","user":"U1","text":"group dm","ts":"1720000000.2"}
                }
                """);
        SlackInboundEvent appHome = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-home",
                  "event":{"type":"message","channel":"D-support","channel_type":"app_home","user":"U1","text":"home","ts":"1720000000.4"}
                }
                """);
        SlackInboundEvent missingType = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-no-type",
                  "event":{"type":"message","channel":"C-support","user":"U1","text":"unknown","ts":"1720000000.5"}
                }
                """);

        assertThat(normalizer.normalize(privateChannel, channelId))
                .isInstanceOf(SlackNormalizer.Accepted.class);
        assertThat(((SlackNormalizer.Filtered) normalizer.normalize(directMessage, channelId)).reason())
                .isEqualTo(SlackNormalizer.FilterReason.UNSUPPORTED_CONVERSATION);
        assertThat(((SlackNormalizer.Filtered) normalizer.normalize(groupDirectMessage, channelId)).reason())
                .isEqualTo(SlackNormalizer.FilterReason.UNSUPPORTED_CONVERSATION);
        assertThat(((SlackNormalizer.Filtered) normalizer.normalize(appHome, channelId)).reason())
                .isEqualTo(SlackNormalizer.FilterReason.UNSUPPORTED_CONVERSATION);
        assertThat(((SlackNormalizer.Filtered) normalizer.normalize(missingType, channelId)).reason())
                .isEqualTo(SlackNormalizer.FilterReason.UNSUPPORTED_CONVERSATION);
    }

    @Test
    void filtersUnsupportedMessageSubtypeAndNonMessageEvents() throws Exception {
        SlackInboundEvent unsupportedSubtype = inbound("""
                {
                  "type":"event_callback",
                  "event_id":"Ev-join",
                  "event":{"type":"message","subtype":"channel_join","channel":"C-support","channel_type":"channel","user":"U1","text":"joined","ts":"1720000000.1"}
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
                  "event":{"type":"message","channel":"C-support","channel_type":"channel","user":"U-customer","ts":"1720000000.1"}
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
