package com.unifiedsupportinbox.realtime.internal;

import java.security.Principal;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

final class AuthenticatedStompChannelInterceptor implements ChannelInterceptor {

    private static final String CASES_DESTINATION = "/topic/cases";
    private static final String CASE_DESTINATION_PREFIX = CASES_DESTINATION + "/";
    private static final String PERSONAL_DESTINATION = "/user/queue/personal";
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command == null || command == StompCommand.DISCONNECT) {
            return message;
        }

        Principal user = accessor.getUser();
        if (user == null) {
            throw new AccessDeniedException("Authenticated WebSocket session is required.");
        }

        if (command == StompCommand.SEND) {
            throw new AccessDeniedException("Client STOMP SEND frames are not allowed.");
        }
        if (command == StompCommand.SUBSCRIBE) {
            authorizeSubscription(accessor.getDestination());
        }
        return message;
    }

    private static void authorizeSubscription(String destination) {
        if (destination == null || destination.isBlank()) {
            throw new AccessDeniedException("Subscription destination is required.");
        }
        if (CASES_DESTINATION.equals(destination) || PERSONAL_DESTINATION.equals(destination)) {
            return;
        }
        if (destination.startsWith(CASE_DESTINATION_PREFIX)
                && UUID.matcher(destination.substring(CASE_DESTINATION_PREFIX.length())).matches()) return;
        throw new AccessDeniedException("Subscription destination is not allowed.");
    }
}
