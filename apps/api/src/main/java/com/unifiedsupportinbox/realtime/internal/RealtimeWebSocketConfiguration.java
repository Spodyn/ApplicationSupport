package com.unifiedsupportinbox.realtime.internal;

import com.unifiedsupportinbox.UsiConfigurationProperties;
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
    private final UsiConfigurationProperties configuration;
    private final AuthenticatedWebSocketHandshakeInterceptor handshakeInterceptor;
    private final AuthenticatedStompChannelInterceptor stompAuthentication =
            new AuthenticatedStompChannelInterceptor();

    RealtimeWebSocketConfiguration(
            RealtimeProperties properties,
            UsiConfigurationProperties configuration,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler taskScheduler) {
        this.properties = properties;
        this.configuration = configuration;
        this.taskScheduler = taskScheduler;
        this.handshakeInterceptor = new AuthenticatedWebSocketHandshakeInterceptor(
                configuration.deployment().profile() == UsiConfigurationProperties.DeploymentProfile.LOCAL
                        ? configuration.publicBaseUrl() : null);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        var endpoint = registry.addEndpoint("/ws").addInterceptors(handshakeInterceptor);
        if (configuration.deployment().profile() == UsiConfigurationProperties.DeploymentProfile.LOCAL) {
            // Spring adds its own origin interceptor after ours. The sole extra
            // allowed origin is the configured local web ingress, while our
            // interceptor still requires a loopback proxy and matching headers.
            endpoint.setAllowedOrigins(configuration.publicBaseUrl().toString());
        }
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
                .setTimeToFirstMessage(properties.timeToFirstMessageMillis())
                .addDecoratorFactory(handler -> new FirstMessageTimeoutWebSocketHandlerDecorator(
                        handler,
                        taskScheduler,
                        properties.timeToFirstMessage()));
    }
}
