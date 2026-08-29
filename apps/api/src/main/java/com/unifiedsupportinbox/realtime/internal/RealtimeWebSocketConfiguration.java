package com.unifiedsupportinbox.realtime.internal;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
class RealtimeWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    private final RealtimeProperties properties;
    private final TaskScheduler taskScheduler;
    private final AuthenticatedWebSocketHandshakeInterceptor handshakeInterceptor =
            new AuthenticatedWebSocketHandshakeInterceptor();
    private final AuthenticatedStompChannelInterceptor stompAuthentication =
            new AuthenticatedStompChannelInterceptor();

    RealtimeWebSocketConfiguration(
            RealtimeProperties properties,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler taskScheduler) {
        this.properties = properties;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(handshakeInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeat = properties.heartbeatMillis();
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(taskScheduler)
                .setHeartbeatValue(new long[] {heartbeat, heartbeat});
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthentication);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(properties.messageSizeLimit())
                .setTimeToFirstMessage(properties.timeToFirstMessageMillis());
    }
}
