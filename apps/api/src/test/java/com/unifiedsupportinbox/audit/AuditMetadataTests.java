package com.unifiedsupportinbox.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AuditMetadataTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void removesCredentialsAndMessageContentAtEveryLevel() throws Exception {
        ObjectNode metadata = (ObjectNode) objectMapper.readTree("""
                {
                  "outcome": "accepted",
                  "messageBody": "do not retain this",
                  "nested": {"authorization": "do not retain", "attempt": 2},
                  "items": [{"content": "do not retain", "kind": "attachment"}]
                }
                """);

        ObjectNode sanitized = AuditMetadata.sanitize(metadata);

        assertThat(sanitized.toString()).isEqualTo("""
                {"outcome":"accepted","nested":{"attempt":2},"items":[{"kind":"attachment"}]}""");
    }
}
