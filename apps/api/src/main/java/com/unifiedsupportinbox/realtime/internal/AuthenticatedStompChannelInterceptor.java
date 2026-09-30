package com.unifiedsupportinbox.realtime.internal;

import java.security.Principal;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

final class AuthenticatedStompChannelInterceptor implements ChannelInterceptor {

    private static final String USER_DESTINATION_PREFIX = "/user/queue/";
    private static final String BROKER_QUEUE_PREFIX = "/queue/";

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

        if (command == StompCommand.SUBSCRIBE) {
            authorizeSubscription(accessor.getDestination());
        }
        return message;
    }

    private static void authorizeSubscription(String destination) {
        if (destination == null || destination.isBlank()) {
            throw new AccessDeniedException("Subscription destination is required.");
        }
        if (destination.startsWith(BROKER_QUEUE_PREFIX)) {
            throw new AccessDeniedException("Direct broker queue subscriptions are not allowed.");
        }
        if (destination.startsWith("/user/") && !destination.startsWith(USER_DESTINATION_PREFIX)) {
            throw new AccessDeniedException("Personal subscriptions must use the current-user destination.");
        }
    }
}
