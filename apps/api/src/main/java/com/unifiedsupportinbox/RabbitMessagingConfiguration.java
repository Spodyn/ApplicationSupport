package com.unifiedsupportinbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RabbitMessagingConfiguration {

    @Bean
    TopicExchange usiOutboxExchange(OutboxProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }
}
