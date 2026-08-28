package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BootstrapAdminRunnerTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void runnerReadsSecretWithoutLoggingItAndWipesMutablePasswordBuffer(CapturedOutput output)
            throws Exception {
        String secret = String.join("-", "test", "only", "bootstrap", "credential");
        Path passwordFile = temporaryDirectory.resolve("bootstrap-admin-password");
        Files.writeString(passwordFile, secret + System.lineSeparator());

        BootstrapAdminService service = mock(BootstrapAdminService.class);
        AtomicReference<char[]> suppliedPassword = new AtomicReference<>();
        UUID userId = UUID.fromString("00000000-0000-7000-8000-000000000063");
        when(service.bootstrap(
                eq("admin@example.com"),
                eq("Bootstrap Administrator"),
                any(char[].class)))
                .thenAnswer(invocation -> {
                    char[] supplied = invocation.getArgument(2);
                    suppliedPassword.set(supplied);
                    assertThat(new String(supplied)).isEqualTo(secret);
                    return userId;
                });

        BootstrapAdminRunner runner = new BootstrapAdminRunner(
                new BootstrapAdminProperties(
                        true,
                        "admin@example.com",
                        "Bootstrap Administrator",
                        passwordFile),
                new BootstrapAdminSecretReader(),
                service);

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(suppliedPassword.get()).isNotNull();
        assertThat(suppliedPassword.get()).containsOnly('\0');
        assertThat(output.getAll()).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(passwordFile.toString());
    }

    @Test
    void malformedSecretFailsBeforeAnyDatabaseBootstrapAndDoesNotEchoContent(CapturedOutput output)
            throws Exception {
        String malformedContent = String.join("", "test-only", "\n", "second-line");
        Path passwordFile = temporaryDirectory.resolve("malformed-bootstrap-admin-password");
        Files.writeString(passwordFile, malformedContent);
        BootstrapAdminService service = mock(BootstrapAdminService.class);

        BootstrapAdminRunner runner = new BootstrapAdminRunner(
                new BootstrapAdminProperties(
                        true,
                        "admin@example.com",
                        "Bootstrap Administrator",
                        passwordFile),
                new BootstrapAdminSecretReader(),
                service);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(BootstrapAdminException.class)
                .hasMessageContaining("exactly one text line");

        verifyNoInteractions(service);
        assertThat(output.getAll()).doesNotContain(malformedContent);
    }
}
