package com.unifiedsupportinbox.readstate.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaseCreatedUnreadRabbitConfigurationTests {

    @Test
    void caseCreatedQueueDeadLettersRejectedMessages() {
        var queue = new CaseCreatedUnreadRabbitConfiguration().caseCreatedUnreadQueue();

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", CaseCreatedUnreadRabbitConfiguration.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", CaseCreatedUnreadRabbitConfiguration.DEAD_LETTER_ROUTING_KEY);
    }
}
