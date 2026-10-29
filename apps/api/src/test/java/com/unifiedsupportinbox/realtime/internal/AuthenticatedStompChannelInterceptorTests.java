package com.unifiedsupportinbox.realtime.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;

class AuthenticatedStompChannelInterceptorTests {

    private final AuthenticatedStompChannelInterceptor interceptor = new AuthenticatedStompChannelInterceptor();
    private final Principal user = () -> "00000000-0000-0000-0000-000000000125";

    @Test
    void allowsGlobalTopicAndCurrentUserQueueSubscriptions() {
        assertThatCode(() -> interceptor.preSend(subscribe("/topic/cases", user), null)).doesNotThrowAnyException();
        assertThatCode(() -> interceptor.preSend(
                subscribe("/topic/cases/00000000-0000-0000-0000-000000000125", user), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> interceptor.preSend(subscribe("/user/queue/personal", user), null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsDirectBrokerQueueSubscription() {
        assertThatThrownBy(() -> interceptor.preSend(subscribe("/queue/personal-user123", user), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsUserIdBearingPersonalDestination() {
        assertThatThrownBy(() -> interceptor.preSend(
                subscribe("/user/other-user/queue/personal", user), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsUnknownOrMalformedTopicSubscriptions() {
        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/internal", user), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/cases/not-a-uuid", user), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsClientSendFramesToTheBroker() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/topic/cases", user), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND frames");
    }

    @Test
    void rejectsUnauthenticatedSubscription() {
        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/cases", null), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Authenticated WebSocket session");
    }

    private static Message<byte[]> subscribe(String destination, Principal principal) {
        return frame(StompCommand.SUBSCRIBE, destination, principal);
    }

    private static Message<byte[]> frame(StompCommand command, String destination, Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        if (command == StompCommand.SUBSCRIBE) accessor.setSubscriptionId("sub-1");
        accessor.setUser(principal);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
