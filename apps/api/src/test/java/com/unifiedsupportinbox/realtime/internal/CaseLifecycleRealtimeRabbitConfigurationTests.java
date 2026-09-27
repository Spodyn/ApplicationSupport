package com.unifiedsupportinbox.realtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

class CaseLifecycleRealtimeRabbitConfigurationTests {

    private final CaseLifecycleRealtimeRabbitConfiguration configuration =
            new CaseLifecycleRealtimeRabbitConfiguration();
    private final TopicExchange outbox = new TopicExchange("usi.events", true, false);

    @Test
    void declaresDurableQueueWithDeadLetterRoute() {
        Queue queue = configuration.caseLifecycleRealtimeQueue();
        assertThat(queue.getName()).isEqualTo(CaseLifecycleRealtimeRabbitConfiguration.QUEUE);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", CaseLifecycleRealtimeRabbitConfiguration.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", CaseLifecycleRealtimeRabbitConfiguration.DEAD_LETTER_ROUTING_KEY);
    }

    @Test
    void bindsCommittedCaseLifecycleEventsThroughOneTopicPattern() {
        Queue queue = configuration.caseLifecycleRealtimeQueue();
        Binding binding = configuration.caseLifecycleRealtimeBinding(queue, outbox);
        assertThat(binding.getDestination()).isEqualTo(CaseLifecycleRealtimeRabbitConfiguration.QUEUE);
        assertThat(binding.getRoutingKey()).isEqualTo(CaseLifecycleRealtimeRabbitConfiguration.CASE_EVENT_PATTERN);
    }

    @Test
    void deadLettersMalformedLifecycleEvents() {
        Queue dead = configuration.caseLifecycleRealtimeDeadLetterQueue();
        DirectExchange exchange = configuration.caseLifecycleRealtimeDeadLetterExchange();
        Binding binding = configuration.caseLifecycleRealtimeDeadLetterBinding(dead, exchange);
        assertThat(dead.isDurable()).isTrue();
        assertThat(binding.getRoutingKey()).isEqualTo(CaseLifecycleRealtimeRabbitConfiguration.DEAD_LETTER_ROUTING_KEY);
    }
}
