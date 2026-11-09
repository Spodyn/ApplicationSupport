package com.unifiedsupportinbox.provider.slack.internal;

import java.util.List;

interface SlackAdministrationApiClient {

    AuthResponse testAuthentication(byte[] botToken);

    ConversationsResponse listConversations(byte[] botToken, String workspaceId, String cursor);

    record AuthResponse(
            int statusCode,
            boolean ok,
            String workspaceId,
            String workspaceName,
            String errorCode) {
    }

    record Conversation(
            String id,
            String name,
            boolean member,
            boolean privateChannel,
            boolean slackConnect) {
    }

    record ConversationsResponse(
            int statusCode,
            boolean ok,
            List<Conversation> channels,
            String nextCursor,
            String errorCode) {
    }
}
