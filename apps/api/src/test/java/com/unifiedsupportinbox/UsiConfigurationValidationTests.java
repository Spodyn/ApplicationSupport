package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class UsiConfigurationValidationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ConfigurationUnderTest.class)
            .withPropertyValues(
                    "usi.deployment.profile=local",
                    "usi.public-base-url=http://localhost:3000",
                    "usi.provider-callbacks.slack=https://example.invalid/api/v1/provider-callbacks/slack",
                    "usi.provider-callbacks.teams=https://example.invalid/api/v1/provider-callbacks/teams",
                    "usi.provider-callbacks.telegram=https://example.invalid/api/v1/provider-callbacks/telegram",
                    "usi.security.cors.allowed-origins=",
                    "usi.object-storage.endpoint=http://localhost:9000",
                    "usi.object-storage.region=us-east-1",
                    "usi.object-storage.attachments-bucket=usi-attachments-test",
                    "usi.object-storage.exports-bucket=usi-exports-test",
                    "usi.integration-secrets.backend=in-memory");

    @Test
    void missingCriticalConfigurationFailsFast() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConfigurationUnderTest.class)
                .withPropertyValues(
                        "usi.deployment.profile=local",
                        "usi.provider-callbacks.slack=https://example.invalid/api/v1/provider-callbacks/slack",
                        "usi.provider-callbacks.teams=https://example.invalid/api/v1/provider-callbacks/teams",
                        "usi.provider-callbacks.telegram=https://example.invalid/api/v1/provider-callbacks/telegram",
                        "usi.security.cors.allowed-origins=",
                        "usi.object-storage.endpoint=http://localhost:9000",
                        "usi.object-storage.region=us-east-1",
                        "usi.object-storage.attachments-bucket=usi-attachments-test",
                        "usi.object-storage.exports-bucket=usi-exports-test",
                        "usi.integration-secrets.backend=in-memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeMessages(context.getStartupFailure()))
                            .contains("publicBaseUrl");
                });
    }

    @Test
    void prodRejectsInsecureRuntimeUrls() {
        contextRunner
                .withPropertyValues(
                        "usi.deployment.profile=prod",
                        "usi.public-base-url=http://localhost:3000",
                        "usi.object-storage.endpoint=http://localhost:9000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeMessages(context.getStartupFailure()))
                            .contains("staging/prod require HTTPS");
                });
    }

    @Test
    void prodRejectsInsecureCorsOrigin() {
        contextRunner
                .withPropertyValues(
                        "usi.deployment.profile=prod",
                        "usi.public-base-url=https://support.example.invalid",
                        "usi.object-storage.endpoint=https://objects.example.invalid",
                        "usi.security.cors.allowed-origins=http://client.example.invalid")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeMessages(context.getStartupFailure()))
                            .contains("CORS origins must use HTTPS");
                });
    }

    @Test
    void validatedLocalShapeStarts() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    private static String causeMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                messages.append(cause.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UsiConfigurationProperties.class)
    static class ConfigurationUnderTest {
    }
}
