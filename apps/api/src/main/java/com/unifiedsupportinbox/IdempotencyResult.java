package com.unifiedsupportinbox;

import tools.jackson.databind.JsonNode;

/** Result of an idempotent command, including whether the response was replayed. */
public record IdempotencyResult(int status, JsonNode body, boolean replayed) {
}
