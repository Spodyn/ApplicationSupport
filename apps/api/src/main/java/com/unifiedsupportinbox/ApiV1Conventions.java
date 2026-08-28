package com.unifiedsupportinbox;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Frozen transport-level conventions shared by all REST v1 endpoints. */
public final class ApiV1Conventions {

    public static final String BASE_PATH = "/api/v1";
    public static final String CURSOR_QUERY_PARAMETER = "cursor";
    public static final String LIMIT_QUERY_PARAMETER = "limit";
    public static final String SORT_QUERY_PARAMETER = "sort";
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 100;
    public static final Duration CURSOR_TTL = Duration.ofHours(24);

    private ApiV1Conventions() {
    }

    public static int pageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (requested < 1 || requested > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return requested;
    }

    public static String formatUtcTimestamp(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("timestamp is required");
        }
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }

    public static Instant parseUtcTimestamp(String value) {
        if (value == null || value.isBlank() || !value.endsWith("Z")) {
            throw new IllegalArgumentException("timestamp must be RFC3339 UTC with a Z suffix");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("timestamp must be RFC3339 UTC with a Z suffix", exception);
        }
    }

    public static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("UUID is required");
        }
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException("UUID must use canonical lower-case text form");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("UUID must use canonical lower-case text form", exception);
        }
    }
}
