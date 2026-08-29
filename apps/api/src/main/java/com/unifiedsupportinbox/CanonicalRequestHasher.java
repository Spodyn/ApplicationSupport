package com.unifiedsupportinbox;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class CanonicalRequestHasher {

    private final ObjectMapper objectMapper;

    CanonicalRequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String hash(Object request) {
        JsonNode tree = objectMapper.valueToTree(request);
        JsonNode canonical = canonicalize(tree);
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(sha256().digest(encoded));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("request cannot be canonicalized for idempotency", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonicalize(value)));
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = objectMapper.createObjectNode();
            List<String> properties = new ArrayList<>(node.propertyNames());
            Collections.sort(properties);
            properties.forEach(property -> object.set(property, canonicalize(node.get(property))));
            return object;
        }
        throw new IllegalArgumentException("unsupported JSON node for idempotency hashing");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
