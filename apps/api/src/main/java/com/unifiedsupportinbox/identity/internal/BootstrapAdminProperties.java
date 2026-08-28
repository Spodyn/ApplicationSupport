package com.unifiedsupportinbox.identity.internal;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "usi.bootstrap-admin")
public record BootstrapAdminProperties(
        boolean enabled,
        String email,
        String displayName,
        Path passwordFile) {

    RequiredConfiguration requiredConfiguration() {
        if (!enabled) {
            throw new BootstrapAdminException("bootstrap administrator trigger is not enabled");
        }

        String requiredEmail = requireText(email, "bootstrap administrator email is required");
        String requiredDisplayName = requireText(
                displayName,
                "bootstrap administrator display name is required");
        if (passwordFile == null
                || !passwordFile.isAbsolute()
                || !passwordFile.equals(passwordFile.normalize())) {
            throw new BootstrapAdminException(
                    "bootstrap administrator password file must be an absolute normalized path");
        }

        return new RequiredConfiguration(requiredEmail, requiredDisplayName, passwordFile);
    }

    private static String requireText(String value, String message) {
        if (value == null) {
            throw new BootstrapAdminException(message);
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new BootstrapAdminException(message);
        }
        return normalized;
    }

    record RequiredConfiguration(String email, String displayName, Path passwordFile) {
    }
}
