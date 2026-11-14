package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.provider.ProviderAttachmentMetadata;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.databind.JsonNode;

record SlackFileReference(String id, String filename) {

    static List<SlackFileReference> from(JsonNode event) {
        JsonNode files = event.path("files");
        if (!files.isArray()) return List.of();
        var references = new LinkedHashMap<String, SlackFileReference>();
        for (JsonNode file : files) {
            JsonNode id = file.path("id");
            if (!id.isTextual() || !id.asText().matches("[A-Za-z0-9_-]{1,255}")) continue;
            String filename = file.path("name").asText("");
            if (filename.isBlank()) filename = file.path("title").asText("");
            // Event metadata is presentation only. Transfer uses the authenticated file API.
            if (filename.isBlank()) filename = id.asText();
            filename = new ProviderAttachmentMetadata(id.asText(), filename, null, 0).filename();
            references.putIfAbsent(id.asText(), new SlackFileReference(id.asText(), filename));
            if (references.size() == 10) break;
        }
        return List.copyOf(references.values());
    }
}
