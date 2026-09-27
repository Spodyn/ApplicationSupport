package com.unifiedsupportinbox.realtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

/** Regression guard: per-user read/snooze signals must never enter the global Case topic. */
class CaseLifecycleRealtimePersonalStateContractTests {

    @Test
    void globalCaseQueueHasNoWildcardThatCouldMatchPersonalState() {
        CaseLifecycleRealtimeRabbitConfiguration configuration =
                new CaseLifecycleRealtimeRabbitConfiguration();
        Queue queue = configuration.caseLifecycleRealtimeQueue();
        TopicExchange exchange = new TopicExchange("usi.events", true, false);

        Set<String> routingKeys = Set.of(
                configuration.caseCreatedRealtimeBinding(queue, exchange).getRoutingKey(),
                configuration.caseClaimedRealtimeBinding(queue, exchange).getRoutingKey(),
                configuration.caseUpdatedRealtimeBinding(queue, exchange).getRoutingKey(),
                configuration.caseSlaChangedRealtimeBinding(queue, exchange).getRoutingKey());

        assertThat(routingKeys)
                .allMatch(key -> !key.contains("*") && !key.contains("#"))
                .doesNotContain("case.unread_changed", "case.snoozed", "case.read_position_changed");
    }
}
