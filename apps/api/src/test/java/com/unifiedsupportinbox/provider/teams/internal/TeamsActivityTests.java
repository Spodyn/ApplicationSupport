package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TeamsActivityTests {
    final ObjectMapper json=new ObjectMapper();
    tools.jackson.databind.node.ObjectNode activity() {
        return (tools.jackson.databind.node.ObjectNode)json.readTree("""
                {"id":"root-1","type":"message","timestamp":"2026-11-19T12:00:00Z","serviceUrl":"https://smba.trafficmanager.net/emea/",
                "from":{"id":"human","name":"Sandbox Customer"},"recipient":{"id":"28:bot"},
                "conversation":{"id":"19:channel;messageid=root-1","conversationType":"channel"},
                "channelData":{"channel":{"id":"19:channel"}},"text":"ordinary message without mention"}
                """);
    }
    @Test void nonMentionRootAndRepliesKeepRootIdentityWhileGroupChatIsLinear() {
        var a=activity(); var root=TeamsActivity.parse(a,"bot");
        assertThat(root.thread()).isEqualTo("root-1"); assertThat(root.channelId()).isEqualTo("19:channel");
        assertThat(root.body()).isEqualTo("ordinary message without mention");
        a.put("id","reply-2").put("replyToId","root-1");
        assertThat(TeamsActivity.parse(a,"bot").thread()).isEqualTo(root.thread());
        a.withObjectProperty("conversation").put("conversationType","groupChat").put("id","19:chat");
        assertThat(TeamsActivity.parse(a,"bot").thread()).isNull();
        assertThat(TeamsActivity.parse(a,"bot").channelId()).isEqualTo("19:chat");
    }
    @Test void unsupportedContextsAndBotActivitiesHaveNoBusinessCommand() {
        for(String type:java.util.List.of("personal","privateChannel","sharedChannel")) {
            var a=activity(); a.withObjectProperty("conversation").put("conversationType",type);
            assertThat(TeamsActivity.parse(a,"bot")).isNull();
        }
        for(String membership:java.util.List.of("private","shared")) {
            var a=activity(); a.withObjectProperty("channelData").withObjectProperty("channel").put("membershipType",membership);
            assertThat(TeamsActivity.parse(a,"bot")).isNull();
        }
        var a=activity(); a.withObjectProperty("from").put("id","28:bot");
        assertThat(TeamsActivity.parse(a,"bot")).isNull();
    }
    @Test void threadedChannelReplyUsesReplyChainEvenWithoutReplyToId() {
        var a=activity();
        a.put("id","reply-2");
        a.withObjectProperty("channelData").withObjectProperty("replyChain").put("id","root-1");
        assertThat(TeamsActivity.parse(a,"bot").thread()).isEqualTo("root-1");
    }
    @Test void connectorConversationIdentityIsAStableFallbackForThreadedReplies() {
        var a=activity();a.put("id","reply-2");
        assertThat(TeamsActivity.parse(a,"bot").thread()).isEqualTo("root-1");
    }
}
