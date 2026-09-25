package com.unifiedsupportinbox.provider.telegram.internal;

import static com.unifiedsupportinbox.InboundEventOutcomeStore.IGNORED_BOT_MESSAGE;
import static com.unifiedsupportinbox.InboundEventOutcomeStore.INACTIVE_CHANNEL;
import static com.unifiedsupportinbox.InboundEventOutcomeStore.UNMAPPED_CHANNEL;
import static com.unifiedsupportinbox.InboundEventOutcomeStore.UNSUPPORTED_PROVIDER_EVENT;

import com.unifiedsupportinbox.InboundEventOutcomeStore;
import com.unifiedsupportinbox.InboundEventProcessor;
import com.unifiedsupportinbox.InboundEventStore;
import com.unifiedsupportinbox.channel.ChannelIngestionPolicy;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Processes durable Telegram updates into the provider-neutral inbound message boundary. */
@Component
class TelegramInboundWorker {
    private final InboundEventProcessor processor;
    private final InboundEventStore events;
    private final InboundEventOutcomeStore outcomes;
    private final ChannelIngestionPolicy channels;
    private final ObjectProvider<InboundMessageCommandHandler> handlers;
    private final ObjectMapper json;

    TelegramInboundWorker(InboundEventProcessor processor, InboundEventStore events, InboundEventOutcomeStore outcomes,
            ChannelIngestionPolicy channels, ObjectProvider<InboundMessageCommandHandler> handlers, ObjectMapper json) {
        this.processor = processor; this.events = events; this.outcomes = outcomes;
        this.channels = channels; this.handlers = handlers; this.json = json;
    }

    void process(UUID eventId) {
        processor.process(eventId, "TELEGRAM_PROCESSING_FAILED", inbound -> {
            if (!"TELEGRAM".equals(inbound.provider())) return;
            TelegramMessage message = decode(inbound);
            if (message == null) { outcomes.mark(inbound.id(), UNSUPPORTED_PROVIDER_EVENT); return; }
            if (message.fromBot()) { outcomes.mark(inbound.id(), IGNORED_BOT_MESSAGE); return; }
            ChannelIngestionPolicy.Decision channel = channels.resolve(inbound.integrationId(), message.chatId()).orElse(null);
            if (channel == null) { outcomes.mark(inbound.id(), UNMAPPED_CHANNEL); return; }
            if (channel.ignoredForInbound()) { outcomes.markIgnoredByChannel(inbound.id()); return; }
            if (!channel.active()) { outcomes.mark(inbound.id(), INACTIVE_CHANNEL); return; }
            InboundMessageCommandHandler handler = handlers.getIfUnique();
            if (handler == null) throw new IllegalStateException("Inbound message handler is unavailable.");
            handler.handle(new InboundMessageCommandHandler.Command(inbound.id(), inbound.integrationId(), channel.channelId(),
                    IntegrationProvider.TELEGRAM, inbound.externalEventId(), message.chatId(), message.messageId(),
                    message.threadKey(), message.authorId(), message.text(), InboundMessageCommandHandler.Mutation.CREATE,
                    message.occurredAt(), inbound.correlationId()));
        });
    }

    private TelegramMessage decode(InboundEventStore.InboundEvent inbound) {
        try {
            JsonNode root = json.readTree(inbound.payloadJson());
            if (root == null || !root.isObject() || !root.path("update_id").isIntegralNumber()
                    || !inbound.externalEventId().equals(root.path("update_id").asText())) throw new IllegalArgumentException("Malformed Telegram update.");
            JsonNode message = root.get("message");
            if (message == null || !message.isObject()) return null;
            if (!message.path("message_id").isIntegralNumber() || !message.path("date").isIntegralNumber()
                    || !message.path("chat").path("id").isIntegralNumber() || !message.path("from").path("id").isIntegralNumber()) throw new IllegalArgumentException("Malformed Telegram message.");
            JsonNode from = message.path("from");
            boolean bot = from.path("is_bot").asBoolean(false);
            String text = message.has("text") && message.path("text").isTextual() ? message.path("text").asText() : "";
            String chatId = message.path("chat").path("id").asText();
            String messageId = message.path("message_id").asText();
            String topic = message.path("message_thread_id").isIntegralNumber() ? message.path("message_thread_id").asText() : null;
            return new TelegramMessage(chatId, messageId, topic == null ? messageId : topic, from.path("id").asText(), text, bot, Instant.ofEpochSecond(message.path("date").asLong()));
        } catch (RuntimeException bad) { throw new IllegalArgumentException("Malformed Telegram update.", bad); }
    }
    private record TelegramMessage(String chatId, String messageId, String threadKey, String authorId, String text, boolean fromBot, Instant occurredAt) {}
}
