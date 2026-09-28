package com.unifiedsupportinbox.realtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

class MessageCreatedRealtimeRabbitConfigurationTests {

    private final MessageCreatedRealtimeRabbitConfiguration configuration =
            new MessageCreatedRealtimeRabbitConfiguration();

    @Test
    void bindsExactlyMessageCreatedAndDeadLettersMalformedEvents() {
        Queue queue = configuration.messageCreatedRealtimeQueue();
        TopicExchange outbox = new TopicExchange("usi.events", true, false);
        Binding binding = configuration.messageCreatedRealtimeBinding(queue, outbox);

        assertThat(queue.isDurable()).isTrue();
        assertThat(binding.getRoutingKey()).isEqualTo("message.created");
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", MessageCreatedRealtimeRabbitConfiguration.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", MessageCreatedRealtimeRabbitConfiguration.DEAD_LETTER_ROUTING_KEY);

        Queue dead = configuration.messageCreatedRealtimeDeadLetterQueue();
        DirectExchange deadExchange = configuration.messageCreatedRealtimeDeadLetterExchange();
        Binding deadBinding = configuration.messageCreatedRealtimeDeadLetterBinding(dead, deadExchange);
        assertThat(dead.isDurable()).isTrue();
        assertThat(deadBinding.getRoutingKey()).isEqualTo(MessageCreatedRealtimeRabbitConfiguration.DEAD_LETTER_ROUTING_KEY);
    }
}
