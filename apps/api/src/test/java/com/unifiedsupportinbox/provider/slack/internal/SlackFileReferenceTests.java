package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SlackFileReferenceTests {

    @Test
    void acceptsMinimalFileReferencesAndDropsDuplicateAndInvalidIds() {
        var event = new ObjectMapper().readTree("""
                {"files":[null, {"name":"missing id"}, {"id":"F123","file_access":"check_file_info"},
                          {"id":"F123","name":"duplicate.txt"}, {"id":"F456","name":"safe/file.txt"}],
                 "root":{"files":[{"id":"FRoot"}]}}
                """);
        assertThat(SlackFileReference.from(event)).containsExactly(
                new SlackFileReference("F123", "F123"), new SlackFileReference("F456", "safe_file.txt"));
    }
}
