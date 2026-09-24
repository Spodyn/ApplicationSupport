package com.unifiedsupportinbox.audit;

import java.util.Locale;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Produces metadata suitable for durable audit history.
 *
 * <p>Audit metadata describes an action; it must never become a second copy of
 * credentials or message content. Producers should pass their metadata through
 * this method before it reaches the audit store.</p>
 */
public final class AuditMetadata {

    private static final Set<String> EXCLUDED_FIELDS = Set.of(
            "authorization", "body", "content", "cookie", "message", "messagebody",
            "password", "secret", "token");

    private AuditMetadata() {
    }

    public static ObjectNode sanitize(JsonNode metadata) {
        ObjectNode sanitized = JsonNodeFactory.instance.objectNode();
        if (metadata == null || !metadata.isObject()) {
            return sanitized;
        }
        for (String property : metadata.propertyNames()) {
            if (!isExcluded(property)) {
                sanitized.set(property, sanitizeValue(metadata.get(property)));
            }
        }
        return sanitized;
    }

    private static JsonNode sanitizeValue(JsonNode value) {
        if (value.isObject()) {
            return sanitize(value);
        }
        if (value.isArray()) {
            ArrayNode sanitized = JsonNodeFactory.instance.arrayNode();
            value.forEach(element -> sanitized.add(sanitizeValue(element)));
            return sanitized;
        }
        return value.deepCopy();
    }

    private static boolean isExcluded(String fieldName) {
        String normalized = fieldName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return EXCLUDED_FIELDS.contains(normalized);
    }
}
