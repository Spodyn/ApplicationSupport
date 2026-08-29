package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.InboundEventProcessor;
import com.unifiedsupportinbox.InboundEventProcessor.ProcessingResult;
import com.unifiedsupportinbox.InboundEventStore;
import com.unifiedsupportinbox.InboundEventStore.InboundEvent;
import com.unifiedsupportinbox.provider.slack.internal.SlackInboundEventHandler.SlackInboundEvent;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class SlackInboundWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlackInboundWorker.class);

    private final InboundEventProcessor processor;
    private final InboundEventStore inboundEvents;
    private final ObjectProvider<SlackInboundEventHandler> handlers;
    private final ObjectMapper json;
    private final SlackInboundWorkerProperties properties;

    SlackInboundWorker(
            InboundEventProcessor processor,
            InboundEventStore inboundEvents,
            ObjectProvider<SlackInboundEventHandler> handlers,
            ObjectMapper json,
            SlackInboundWorkerProperties properties) {
        this.processor = processor;
        this.inboundEvents = inboundEvents;
        this.handlers = handlers;
        this.json = json;
        this.properties = properties;
    }

    AttemptResult process(UUID eventId) {
        InboundEvent before = inboundEvents.findById(eventId).orElse(null);
        if (before == null || !"SLACK".equals(before.provider())) return AttemptResult.NOT_CLAIMED;
        if ("PROCESSED".equals(before.status())) return AttemptResult.ALREADY_PROCESSED;
        if ("DLQ".equals(before.status())) return AttemptResult.DLQ;

        SlackInboundEventHandler handler = handlers.getIfUnique();
        try {
            ProcessingResult result = processor.process(eventId, "SLACK_PROCESSING_FAILED", inbound -> {
                SlackInboundEvent decoded = decode(inbound);
                if (handler == null) {
                    throw SlackInboundProcessingException.transientFailure(
                            "HANDLER_UNAVAILABLE",
                            "Slack inbound handler is not available.");
                }
                handler.handle(decoded);
            });
            return result == ProcessingResult.PROCESSED
                    ? AttemptResult.PROCESSED
                    : AttemptResult.ALREADY_PROCESSED;
        } catch (RuntimeException failure) {
            InboundEvent failed = inboundEvents.findById(eventId).orElseThrow();
            if ("PROCESSED".equals(failed.status())) return AttemptResult.ALREADY_PROCESSED;
            if ("DLQ".equals(failed.status())) return AttemptResult.DLQ;

            FailureDisposition disposition = classify(failure);
            if (disposition.category() != FailureCategory.TRANSIENT) {
                if (!inboundEvents.moveToDlq(eventId, disposition.category().name(), disposition.errorCode())) {
                    throw new IllegalStateException("Slack inbound event could not be moved to DLQ: " + eventId, failure);
                }
                return AttemptResult.DLQ;
            }

            if (failed.attempts() >= properties.maxAttempts()) {
                if (!inboundEvents.moveToDlq(eventId, "EXHAUSTED", disposition.errorCode())) {
                    throw new IllegalStateException("Slack inbound event could not exhaust to DLQ: " + eventId, failure);
                }
                return AttemptResult.DLQ;
            }

            Duration delay = retryDelay(failed.attempts());
            if (!inboundEvents.scheduleRetry(eventId, disposition.errorCode(), delay)) {
                throw new IllegalStateException("Slack inbound retry could not be scheduled: " + eventId, failure);
            }
            LOGGER.warn(
                    "Slack inbound processing failed; eventId={}, externalEventId={}, attempt={}, retryInMs={}, code={}",
                    eventId,
                    failed.externalEventId(),
                    failed.attempts(),
                    delay.toMillis(),
                    disposition.errorCode());
            return AttemptResult.RETRY_SCHEDULED;
        }
    }

    private SlackInboundEvent decode(InboundEvent inbound) {
        JsonNode root;
        try {
            root = json.readTree(inbound.payloadJson());
        } catch (JacksonException exception) {
            throw SlackInboundProcessingException.malformed(
                    "MALFORMED_JSON", "Persisted Slack callback is not valid JSON.");
        }
        if (root == null || !root.isObject()) {
            throw SlackInboundProcessingException.malformed(
                    "MALFORMED_ENVELOPE", "Persisted Slack callback must be an object.");
        }
        JsonNode type = root.get("type");
        JsonNode eventId = root.get("event_id");
        JsonNode event = root.get("event");
        if (type == null || !type.isTextual() || !"event_callback".equals(type.stringValue())) {
            throw SlackInboundProcessingException.malformed(
                    "INVALID_CALLBACK_TYPE", "Persisted Slack callback type is invalid.");
        }
        if (eventId == null || !eventId.isTextual()
                || !inbound.externalEventId().equals(eventId.stringValue())) {
            throw SlackInboundProcessingException.malformed(
                    "EVENT_ID_MISMATCH", "Persisted Slack event_id does not match the durable key.");
        }
        if (event == null || !event.isObject()) {
            throw SlackInboundProcessingException.malformed(
                    "MALFORMED_EVENT", "Persisted Slack callback event must be an object.");
        }
        return new SlackInboundEvent(
                inbound.id(),
                inbound.integrationId(),
                inbound.externalEventId(),
                root,
                event,
                inbound.correlationId());
    }

    private FailureDisposition classify(RuntimeException failure) {
        if (failure instanceof SlackInboundProcessingException classified) {
            return switch (classified.kind()) {
                case TRANSIENT -> new FailureDisposition(FailureCategory.TRANSIENT, classified.errorCode());
                case PERMANENT -> new FailureDisposition(FailureCategory.PERMANENT, classified.errorCode());
                case MALFORMED -> new FailureDisposition(FailureCategory.MALFORMED, classified.errorCode());
            };
        }
        return new FailureDisposition(FailureCategory.TRANSIENT, "UNEXPECTED_PROCESSING_FAILURE");
    }

    private Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 20);
        Duration delay;
        try {
            delay = properties.baseRetryDelay().multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            delay = properties.maxRetryDelay();
        }
        return delay.compareTo(properties.maxRetryDelay()) > 0
                ? properties.maxRetryDelay()
                : delay;
    }

    enum AttemptResult {
        NOT_CLAIMED,
        PROCESSED,
        ALREADY_PROCESSED,
        RETRY_SCHEDULED,
        DLQ
    }

    private enum FailureCategory {
        TRANSIENT,
        PERMANENT,
        MALFORMED
    }

    private record FailureDisposition(FailureCategory category, String errorCode) {
    }
}
