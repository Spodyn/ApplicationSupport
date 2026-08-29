package com.unifiedsupportinbox.provider.internal;

import com.unifiedsupportinbox.UsiConfigurationProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves provider credentials from the external integration-secret boundary.
 * Secret values are returned only as transient bytes and are never logged or
 * copied into Spring's Environment.
 */
@Component
public class ConfiguredProviderSecretResolver {

    private static final int MAX_SECRET_BYTES = 16 * 1024;
    private static final Pattern SAFE_REFERENCE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._/-]{0,511}");
    private static final Pattern SAFE_KEY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final UsiConfigurationProperties configuration;

    public ConfiguredProviderSecretResolver(UsiConfigurationProperties configuration) {
        this.configuration = configuration;
    }

    public Optional<byte[]> resolve(String secretRef, String key) {
        if (!safeReference(secretRef) || !safeKey(key)) {
            return Optional.empty();
        }

        UsiConfigurationProperties.IntegrationSecrets secrets = configuration.integrationSecrets();
        if (secrets.backend() == UsiConfigurationProperties.SecretBackend.IN_MEMORY) {
            return Optional.empty();
        }
        if (secrets.directory() == null) {
            return Optional.empty();
        }

        try {
            Path root = secrets.directory().toRealPath();
            Path unresolved = root.resolve(secretRef).resolve(key).normalize();
            if (!unresolved.startsWith(root)) {
                return Optional.empty();
            }
            Path resolved = unresolved.toRealPath();
            if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
                return Optional.empty();
            }
            long size = Files.size(resolved);
            if (size <= 0 || size > MAX_SECRET_BYTES) {
                return Optional.empty();
            }

            byte[] fileBytes = Files.readAllBytes(resolved);
            try {
                int start = 0;
                int end = fileBytes.length;
                while (start < end && asciiWhitespace(fileBytes[start])) start++;
                while (end > start && asciiWhitespace(fileBytes[end - 1])) end--;
                if (start == end) return Optional.empty();
                for (int index = start; index < end; index++) {
                    if (fileBytes[index] == '\n' || fileBytes[index] == '\r') {
                        return Optional.empty();
                    }
                }
                return Optional.of(Arrays.copyOfRange(fileBytes, start, end));
            } finally {
                Arrays.fill(fileBytes, (byte) 0);
            }
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static boolean asciiWhitespace(byte value) {
        return value == ' '
                || value == '\t'
                || value == '\n'
                || value == '\r'
                || value == '\f';
    }

    private static boolean safeReference(String value) {
        if (value == null || value.isBlank() || value.contains("\\") || !SAFE_REFERENCE.matcher(value).matches()) {
            return false;
        }
        try {
            Path path = Path.of(value);
            if (path.isAbsolute()) return false;
            for (Path segment : path) {
                String text = segment.toString();
                if (".".equals(text) || "..".equals(text)) return false;
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean safeKey(String value) {
        return value != null && SAFE_KEY.matcher(value).matches();
    }
}
