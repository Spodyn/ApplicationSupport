package com.unifiedsupportinbox.identity.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.springframework.stereotype.Component;

@Component
class BootstrapAdminSecretReader {

    private static final long MAX_SECRET_BYTES = 4096;

    char[] readPassword(Path passwordFile) {
        try {
            if (Files.isSymbolicLink(passwordFile)) {
                throw new BootstrapAdminException(
                        "bootstrap administrator password file must not be a symbolic link");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    passwordFile,
                    BasicFileAttributes.class,
                    NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()
                    || attributes.size() <= 0
                    || attributes.size() > MAX_SECRET_BYTES) {
                throw new BootstrapAdminException(
                        "bootstrap administrator password file must be a non-empty bounded regular file");
            }

            String value = removeSingleTrailingLineEnding(
                    Files.readString(passwordFile, StandardCharsets.UTF_8));
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
                throw new BootstrapAdminException(
                        "bootstrap administrator password file must contain exactly one text line");
            }
            if (value.isBlank()) {
                throw new BootstrapAdminException(
                        "bootstrap administrator password must not be blank");
            }
            int characterCount = value.codePointCount(0, value.length());
            if (characterCount < 12 || characterCount > 128) {
                throw new BootstrapAdminException(
                        "bootstrap administrator password must contain 12 to 128 Unicode characters");
            }
            return value.toCharArray();
        } catch (BootstrapAdminException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw new BootstrapAdminException(
                    "bootstrap administrator password file could not be read safely",
                    exception);
        }
    }

    private static String removeSingleTrailingLineEnding(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
