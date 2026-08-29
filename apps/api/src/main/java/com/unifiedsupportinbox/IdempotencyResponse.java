package com.unifiedsupportinbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/** Response persisted for deterministic replay after the business transaction commits. */
public record IdempotencyResponse(int status, JsonNode body) {

    public IdempotencyResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("response status must be between 100 and 599");
        }
        body = body == null ? NullNode.getInstance() : body;
    }
}
