package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Mutation;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Canonical grouping identity is independent of Bot Connector's per-thread conversation ID. */
record TeamsActivity(String id, String channelId, String conversationId, String thread,
        String author, String authorName, String body, Mutation mutation, Instant occurredAt,
        String serviceUrl, String botId, String contextType) {
    static TeamsActivity parse(JsonNode a, String clientId) {
        String type = text(a, "type");
        if (!java.util.Set.of("message", "messageUpdate", "messageDelete").contains(type)) return null;
        String author = text(a.path("from"), "id");
        if (author.equals(clientId) || author.equals("28:" + clientId) || author.startsWith("28:")) return null;
        String conversationType = text(a.path("conversation"), "conversationType");
        String conversation = text(a.path("conversation"), "id");
        String channel, thread, context;
        if ("channel".equals(conversationType)) {
            String membership = a.path("channelData").path("channel").path("membershipType").asText("standard");
            if (!"standard".equalsIgnoreCase(membership)) return null;
            channel = text(a.path("channelData").path("channel"), "id");
            thread = a.path("channelData").path("replyChain").path("id").asText("");
            if (thread.isBlank()) {
                int marker=conversation.indexOf(";messageid=");
                if (marker>=0) {
                    thread=conversation.substring(marker+11);
                    int next=thread.indexOf(';');
                    if (next>=0) thread=thread.substring(0,next);
                }
            }
            if (thread.isBlank()) thread = a.path("replyToId").asText("");
            if (thread.isBlank()) thread = text(a, "id");
            context = "standardChannel";
        } else if ("groupChat".equals(conversationType)) {
            channel = conversation; thread = null; context = "groupChat";
        } else return null;
        Mutation mutation = switch (type) { case "messageUpdate" -> Mutation.EDIT; case "messageDelete" -> Mutation.DELETE; default -> Mutation.CREATE; };
        String body = mutation == Mutation.DELETE ? null : a.path("text").asText("");
        // USI stores plain text; Teams' HTML attachments are never rendered as trusted markup.
        if (body != null && "xml".equals(a.path("textFormat").asText()))
            body = org.springframework.web.util.HtmlUtils.htmlUnescape(body.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]*>", ""));
        return new TeamsActivity(text(a, "id"), channel, conversation, thread, author,
                a.path("from").path("name").asText(null), body, mutation,
                timestamp(text(a, "timestamp")), text(a, "serviceUrl"),
                text(a.path("recipient"), "id"), context);
    }
    private static Instant timestamp(String value) {
        try { return Instant.parse(value); }
        catch(java.time.DateTimeException malformed) { throw new IllegalArgumentException("Malformed Teams timestamp."); }
    }
    static String text(JsonNode node, String name) {
        String value = node.path(name).asText("");
        if (value.isBlank() || value.length() > 2048) throw new IllegalArgumentException("Malformed Teams activity field: " + name);
        return value;
    }
}
