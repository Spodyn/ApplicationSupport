package com.unifiedsupportinbox;

import java.util.List;

import com.unifiedsupportinbox.OutboxEventStore.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventStore store;
    private final RabbitMqOutboxTransport transport;
    private final OutboxProperties properties;

    public OutboxRelay(
            OutboxEventStore store,
            RabbitMqOutboxTransport transport,
            OutboxProperties properties) {
        this.store = store;
        this.transport = transport;
        this.properties = properties;
    }

    public BatchResult publishDueBatch() {
        List<OutboxEvent> claimed = store.claimDue(properties.batchSize(), properties.claimLease());
        int published = 0;
        int retryScheduled = 0;

        for (OutboxEvent event : claimed) {
            try {
                transport.publish(event);
                if (!store.markPublished(event.id())) {
                    throw new IllegalStateException(
                            "outbox event lost PROCESSING ownership before publish checkpoint: " + event.id());
                }
                published++;
            } catch (RuntimeException publishFailure) {
                boolean released = store.releaseForRetry(event.id(), properties.retryDelay());
                retryScheduled += released ? 1 : 0;
                LOGGER.warn(
                        "Outbox publish failed; eventId={}, type={}, attempt={}, retryScheduled={}",
                        event.id(),
                        event.type(),
                        event.attempts(),
                        released,
                        publishFailure);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }

        return new BatchResult(claimed.size(), published, retryScheduled);
    }

    public record BatchResult(int claimed, int published, int retryScheduled) {
    }
}

@Component
@ConditionalOnProperty(
        prefix = "usi.outbox",
        name = "relay-enabled",
        havingValue = "true",
        matchIfMissing = true)
final class ScheduledOutboxRelay {

    private final OutboxRelay relay;

    ScheduledOutboxRelay(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${usi.outbox.poll-interval:1s}")
    void publishDueEvents() {
        relay.publishDueBatch();
    }
}
