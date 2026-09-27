package com.unifiedsupportinbox.realtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
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
    void bindsOnlyGlobalLifecycleEventsAndNeverPersonalUnreadState() {
        Queue queue = configuration.caseLifecycleRealtimeQueue();
        Set<String> routingKeys = Set.of(
                configuration.caseCreatedRealtimeBinding(queue, outbox).getRoutingKey(),
                configuration.caseClaimedRealtimeBinding(queue, outbox).getRoutingKey(),
                configuration.caseUpdatedRealtimeBinding(queue, outbox).getRoutingKey(),
                configuration.caseSlaChangedRealtimeBinding(queue, outbox).getRoutingKey());

        assertThat(routingKeys).containsExactlyInAnyOrder(
                "case.created", "case.claimed", "case.updated", "case.sla_changed");
        assertThat(routingKeys).doesNotContain("case.unread_changed", "case.*", "case.#");
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
