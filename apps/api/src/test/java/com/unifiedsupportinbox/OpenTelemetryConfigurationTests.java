package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class OpenTelemetryConfigurationTests {

    @Test
    void enablesCompleteW3cTracingAndRabbitPropagationWithoutACommittedCollector() throws IOException {
        String properties = new ClassPathResource("application.properties")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(properties)
                .contains("management.tracing.sampling.probability=1.0")
                .contains("management.opentelemetry.tracing.sampler=parent-based-always-on")
                .contains("management.tracing.propagation.type=w3c")
                .contains("spring.task.execution.propagate-context=true")
                .contains("spring.rabbitmq.template.observation-enabled=true")
                .contains("spring.rabbitmq.listener.simple.observation-enabled=true")
                .doesNotContain("management.opentelemetry.tracing.export.otlp.endpoint=");
    }

    @Test
    void testProfileDisablesExternalOpenTelemetrySdkBehavior() throws IOException {
        String properties = new ClassPathResource("application-test.properties")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(properties).contains("management.opentelemetry.enabled=false");
    }
}
