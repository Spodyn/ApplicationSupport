package com.unifiedsupportinbox.identity.internal;

import java.util.Objects;

final class PasswordPolicy {

    static final int MINIMUM_CODE_POINTS = 12;
    static final int MAXIMUM_CODE_POINTS = 128;

    private PasswordPolicy() {
    }

    static void validateForStorage(char[] raw) {
        Objects.requireNonNull(raw, "raw");
        int characterCount = Character.codePointCount(raw, 0, raw.length);
        if (characterCount < MINIMUM_CODE_POINTS || characterCount > MAXIMUM_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "password must contain 12 to 128 Unicode characters");
        }
        if (containsUnsupportedControl(raw)) {
            throw new IllegalArgumentException("password must contain exactly one text line");
        }
    }

    static boolean acceptsAuthenticationInput(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        int characterCount = raw.codePointCount(0, raw.length());
        return characterCount <= MAXIMUM_CODE_POINTS && !containsUnsupportedControl(raw);
    }

    private static boolean containsUnsupportedControl(char[] raw) {
        for (char character : raw) {
            if (character == '\n' || character == '\r' || character == '\0') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUnsupportedControl(String raw) {
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character == '\n' || character == '\r' || character == '\0') {
                return true;
            }
        }
        return false;
    }
}
