package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Command;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Mutation;
import com.unifiedsupportinbox.provider.slack.internal.SlackInboundEventHandler.SlackInboundEvent;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Maps supported Slack Events API message semantics into the provider-neutral inbound contract. */
@Component
class SlackNormalizer {

    Result normalize(SlackInboundEvent inbound, UUID channelId) {
        JsonNode event = inbound.event();
        String type = requiredText(event, "type", "Slack event type is required.");
        if (!"message".equals(type)) {
            return new Filtered(FilterReason.UNSUPPORTED_EVENT);
        }

        String channel = requiredText(event, "channel", "Slack message channel is required.");
        String subtype = optionalText(event, "subtype");
        if (subtype == null) {
            return normalizeCreate(inbound, channelId, channel, event);
        }
        return switch (subtype) {
            case "bot_message" -> new Filtered(FilterReason.BOT_OR_APP_MESSAGE);
            case "message_changed" -> normalizeEdit(inbound, channelId, channel, event);
            case "message_deleted" -> normalizeDelete(inbound, channelId, channel, event);
            default -> new Filtered(FilterReason.UNSUPPORTED_EVENT);
        };
    }

    private Result normalizeCreate(
            SlackInboundEvent inbound,
            UUID channelId,
            String channel,
            JsonNode message) {
        String author = optionalText(message, "user");
        if (isBotLike(inbound, message, author)) {
            return new Filtered(FilterReason.BOT_OR_APP_MESSAGE);
        }
        if (author == null) {
            throw malformed("MALFORMED_SLACK_MESSAGE", "Slack human message user is required.");
        }
        String messageTs = requiredText(message, "ts", "Slack message ts is required.");
        String body = requiredTextAllowEmpty(message, "text", "Slack message text is required.");
        String threadKey = optionalText(message, "thread_ts");
        if (threadKey == null) threadKey = messageTs;

        return accepted(inbound, channelId, channel, messageTs, threadKey, author, body,
                Mutation.CREATE, parseSlackTimestamp(messageTs));
    }

    private Result normalizeEdit(
            SlackInboundEvent inbound,
            UUID channelId,
            String channel,
            JsonNode event) {
        JsonNode message = requiredObject(event, "message", "Slack message_changed message is required.");
        String author = optionalText(message, "user");
        if (isBotLike(inbound, message, author)) {
            return new Filtered(FilterReason.BOT_OR_APP_MESSAGE);
        }
        if (author == null) {
            throw malformed("MALFORMED_SLACK_EDIT", "Slack edited human message user is required.");
        }
        String messageTs = requiredText(message, "ts", "Slack edited message ts is required.");
        String body = requiredTextAllowEmpty(message, "text", "Slack edited message text is required.");
        String threadKey = optionalText(message, "thread_ts");
        if (threadKey == null) threadKey = messageTs;

        JsonNode edited = message.get("edited");
        String occurredAt = edited != null && edited.isObject() ? optionalText(edited, "ts") : null;
        if (occurredAt == null) occurredAt = optionalText(event, "event_ts");
        if (occurredAt == null) occurredAt = messageTs;

        return accepted(inbound, channelId, channel, messageTs, threadKey, author, body,
                Mutation.EDIT, parseSlackTimestamp(occurredAt));
    }

    private Result normalizeDelete(
            SlackInboundEvent inbound,
            UUID channelId,
            String channel,
            JsonNode event) {
        JsonNode previous = requiredObject(
                event, "previous_message", "Slack message_deleted previous_message is required.");
        String author = optionalText(previous, "user");
        if (isBotLike(inbound, previous, author)) {
            return new Filtered(FilterReason.BOT_OR_APP_MESSAGE);
        }
        if (author == null) {
            throw malformed("MALFORMED_SLACK_DELETE", "Slack deleted human message user is required.");
        }

        String deletedTs = optionalText(event, "deleted_ts");
        if (deletedTs == null) deletedTs = requiredText(previous, "ts", "Slack deleted message ts is required.");
        String threadKey = optionalText(previous, "thread_ts");
        if (threadKey == null) threadKey = deletedTs;
        String occurredAt = optionalText(event, "event_ts");
        if (occurredAt == null) occurredAt = deletedTs;

        return accepted(inbound, channelId, channel, deletedTs, threadKey, author, null,
                Mutation.DELETE, parseSlackTimestamp(occurredAt));
    }

    private static Accepted accepted(
            SlackInboundEvent inbound,
            UUID channelId,
            String channel,
            String messageId,
            String threadKey,
            String author,
            String body,
            Mutation mutation,
            Instant occurredAt) {
        return new Accepted(new Command(
                inbound.inboundEventId(),
                inbound.integrationId(),
                channelId,
                IntegrationProvider.SLACK,
                inbound.externalEventId(),
                channel,
                messageId,
                threadKey,
                author,
                body,
                mutation,
                occurredAt,
                inbound.correlationId()));
    }

    private static boolean isBotLike(SlackInboundEvent inbound, JsonNode message, String author) {
        if (presentText(message, "bot_id") || presentText(message, "app_id") || message.has("bot_profile")) {
            return true;
        }
        String subtype = optionalText(message, "subtype");
        if ("bot_message".equals(subtype)) return true;
        if (author == null) return false;

        JsonNode authorizations = inbound.callback().get("authorizations");
        if (authorizations == null || !authorizations.isArray()) return false;
        for (JsonNode authorization : authorizations) {
            if (authorization != null && authorization.isObject()
                    && author.equals(optionalText(authorization, "user_id"))) {
                return true;
            }
        }
        return false;
    }

    private static Instant parseSlackTimestamp(String value) {
        try {
            int dot = value.indexOf('.');
            String secondsPart = dot < 0 ? value : value.substring(0, dot);
            String fraction = dot < 0 ? "" : value.substring(dot + 1);
            if (secondsPart.isBlank() || fraction.length() > 9 || !secondsPart.chars().allMatch(Character::isDigit)
                    || !fraction.chars().allMatch(Character::isDigit)) {
                throw new NumberFormatException("invalid Slack timestamp");
            }
            long seconds = Long.parseLong(secondsPart);
            int nanos = fraction.isEmpty()
                    ? 0
                    : Integer.parseInt((fraction + "000000000").substring(0, 9));
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (NumberFormatException | DateTimeException exception) {
            throw malformed("MALFORMED_SLACK_TIMESTAMP", "Slack timestamp is invalid.");
        }
    }

    private static JsonNode requiredObject(JsonNode object, String field, String detail) {
        JsonNode value = object.get(field);
        if (value == null || !value.isObject()) {
            throw malformed("MALFORMED_SLACK_MESSAGE", detail);
        }
        return value;
    }

    private static String requiredText(JsonNode object, String field, String detail) {
        String value = optionalText(object, field);
        if (value == null) throw malformed("MALFORMED_SLACK_MESSAGE", detail);
        return value;
    }

    private static String requiredTextAllowEmpty(JsonNode object, String field, String detail) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw malformed("MALFORMED_SLACK_MESSAGE", detail);
        }
        return value.stringValue();
    }

    private static String optionalText(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        return value != null && value.isTextual() && !value.stringValue().isBlank()
                ? value.stringValue()
                : null;
    }

    private static boolean presentText(JsonNode object, String field) {
        return optionalText(object, field) != null;
    }

    private static SlackInboundProcessingException malformed(String code, String detail) {
        return SlackInboundProcessingException.malformed(code, detail);
    }

    sealed interface Result permits Accepted, Filtered {
    }

    record Accepted(Command command) implements Result {
    }

    record Filtered(FilterReason reason) implements Result {
    }

    enum FilterReason {
        BOT_OR_APP_MESSAGE,
        UNSUPPORTED_EVENT
    }
}
