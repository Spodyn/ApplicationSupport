package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class StructuredLoggingConfigurationTests {

    @Test
    void sharedConfigurationUsesMachineParseableLogsAndContext() throws IOException {
        String properties = new ClassPathResource("application.properties")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(properties)
                .contains("logging.structured.format.console=logstash")
                .contains("logging.structured.json.context.include=true")
                .contains("logging.structured.json.add.service=${spring.application.name}")
                .contains("logging.level.root=INFO");
    }

    @Test
    void localProfileHasExplicitlyMoreVerboseStructuredDiagnostics() throws IOException {
        String properties = new ClassPathResource("application-local.properties")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(properties).contains("logging.level.root=DEBUG");
    }
}
