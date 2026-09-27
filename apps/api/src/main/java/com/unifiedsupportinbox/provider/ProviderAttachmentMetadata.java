package com.unifiedsupportinbox.provider;

import java.util.Locale;

/**
 * Sanitized provider attachment metadata safe to hand to the common storage
 * pipeline. It deliberately contains no provider URL, token or authorization
 * material.
 */
public record ProviderAttachmentMetadata(
        String providerFileId,
        String filename,
        String contentType,
        long sizeBytes) {

    public ProviderAttachmentMetadata {
        providerFileId = requireIdentifier(providerFileId, "providerFileId");
        filename = normalizeFilename(filename);
        contentType = normalizeContentType(contentType);
        if (sizeBytes < 0 || sizeBytes > ProviderAttachmentGateway.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("sizeBytes must be between 0 and 25 MiB.");
        }
    }

    static String requireIdentifier(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required.");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 512 || containsControl(normalized)) {
            throw new IllegalArgumentException(field + " is invalid.");
        }
        return normalized;
    }

    private static String normalizeFilename(String value) {
        if (value == null) return "attachment";
        String normalized = value.strip()
                .replace('\\', '_')
                .replace('/', '_');
        StringBuilder safe = new StringBuilder(Math.min(normalized.length(), 255));
        normalized.codePoints().forEach(codePoint -> {
            if (safe.length() >= 255) return;
            if (Character.isISOControl(codePoint)) {
                safe.append('_');
            } else {
                safe.appendCodePoint(codePoint);
            }
        });
        String result = safe.toString().strip();
        if (result.isEmpty() || ".".equals(result) || "..".equals(result)) return "attachment";
        return result;
    }

    private static String normalizeContentType(String value) {
        if (value == null || value.isBlank()) return "application/octet-stream";
        String mediaType = value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (mediaType.isEmpty()
                || mediaType.length() > 200
                || containsControl(mediaType)
                || mediaType.indexOf('/') <= 0
                || mediaType.endsWith("/")) {
            return "application/octet-stream";
        }
        return mediaType;
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
