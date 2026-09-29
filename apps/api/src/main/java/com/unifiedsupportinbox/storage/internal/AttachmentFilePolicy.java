package com.unifiedsupportinbox.storage.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class AttachmentFilePolicy {

    static final long MAX_FILE_BYTES = 25L * 1024L * 1024L;
    static final int SNIFF_BYTES = 8192;

    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
            "apk", "app", "bat", "bin", "cmd", "com", "cpl", "dll", "dmg", "exe",
            "hta", "img", "iso", "jar", "js", "jse", "lnk", "mjs", "msi", "ps1",
            "scr", "sh", "vbe", "vbs", "wsf", "wsh");

    private static final Map<String, Set<String>> SAFE_EXTENSIONS = Map.of(
            "application/pdf", Set.of("pdf"),
            "image/png", Set.of("png"),
            "image/jpeg", Set.of("jpg", "jpeg"),
            "image/gif", Set.of("gif"),
            "text/plain", Set.of("txt", "csv", "json", "md", "log"));

    String normalizeFilename(String value) {
        String source = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        StringBuilder safe = new StringBuilder(Math.min(Math.max(source.length(), 10), 255));
        source.codePoints().forEach(codePoint -> {
            if (safe.length() >= 255) return;
            if (codePoint == '/' || codePoint == '\\' || Character.isISOControl(codePoint)) {
                safe.append('_');
            } else {
                safe.appendCodePoint(codePoint);
            }
        });
        String normalized = stripUnsafeEdgeCharacters(safe.toString());
        if (normalized.isBlank() || ".".equals(normalized) || "..".equals(normalized)) {
            return "attachment";
        }
        if (isWindowsReservedBasename(normalized)) {
            normalized = "_" + normalized;
        }
        return normalized;
    }

    String detectContentType(InputStream content) throws IOException {
        byte[] sample = content.readNBytes(SNIFF_BYTES);
        if (startsWith(sample, new int[] {0x25, 0x50, 0x44, 0x46, 0x2d})) return "application/pdf";
        if (startsWith(sample, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) return "image/png";
        if (startsWith(sample, new int[] {0xff, 0xd8, 0xff})) return "image/jpeg";
        if (startsWithAscii(sample, "GIF87a") || startsWithAscii(sample, "GIF89a")) return "image/gif";
        if (startsWith(sample, new int[] {0x50, 0x4b, 0x03, 0x04})
                || startsWith(sample, new int[] {0x50, 0x4b, 0x05, 0x06})
                || startsWith(sample, new int[] {0x50, 0x4b, 0x07, 0x08})) return "application/zip";
        if (startsWith(sample, new int[] {0x1f, 0x8b})) return "application/gzip";
        if (startsWithAscii(sample, "MZ")) return "application/x-dosexec";
        if (startsWith(sample, new int[] {0x7f, 0x45, 0x4c, 0x46})) return "application/x-elf";
        if (startsWithAscii(sample, "#!")) return "application/x-shellscript";
        if (looksLikeUtf8Text(sample)) return "text/plain";
        return "application/octet-stream";
    }

    void validate(String filename, String declaredContentType, String detectedContentType, long sizeBytes)
            throws AttachmentPolicyViolationException {
        if (sizeBytes < 0 || sizeBytes > MAX_FILE_BYTES) {
            throw new AttachmentPolicyViolationException(AttachmentPolicyViolationException.Code.TOO_LARGE);
        }
        validateExtension(filename);
        if ("application/zip".equals(detectedContentType) || "application/gzip".equals(detectedContentType)) {
            throw new AttachmentPolicyViolationException(AttachmentPolicyViolationException.Code.BLOCKED_ARCHIVE);
        }
        if ("application/x-dosexec".equals(detectedContentType)
                || "application/x-elf".equals(detectedContentType)
                || "application/x-shellscript".equals(detectedContentType)) {
            throw new AttachmentPolicyViolationException(AttachmentPolicyViolationException.Code.BLOCKED_EXECUTABLE);
        }
        Set<String> allowedExtensions = SAFE_EXTENSIONS.get(detectedContentType);
        if (allowedExtensions == null) {
            throw new AttachmentPolicyViolationException(AttachmentPolicyViolationException.Code.UNSUPPORTED_TYPE);
        }

        String extension = finalExtension(filename);
        if (extension != null && !allowedExtensions.contains(extension)) {
            throw new AttachmentPolicyViolationException(AttachmentPolicyViolationException.Code.MIME_MISMATCH);
        }

        String declared = normalizeContentType(declaredContentType);
        if (!declaredCompatible(declared, detectedContentType)) {
            throw new AttachmentPolicyViolationException(AttachmentPolicyViolationException.Code.MIME_MISMATCH);
        }
    }

    private static void validateExtension(String filename) throws AttachmentPolicyViolationException {
        String lower = filename.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("\\.");
        for (int index = 1; index < parts.length; index++) {
            if (DANGEROUS_EXTENSIONS.contains(parts[index])) {
                throw new AttachmentPolicyViolationException(AttachmentPolicyViolationException.Code.BLOCKED_EXTENSION);
            }
        }
    }

    private static String finalExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeContentType(String value) {
        if (value == null || value.isBlank()) return "application/octet-stream";
        String result = value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(result) || "image/pjpeg".equals(result)) return "image/jpeg";
        return result;
    }

    private static boolean declaredCompatible(String declared, String detected) {
        if ("application/octet-stream".equals(declared)) return true;
        if (declared.equals(detected)) return true;
        return "text/plain".equals(detected)
                && Set.of("text/csv", "application/json", "text/markdown").contains(declared);
    }

    private static boolean startsWith(byte[] value, int[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (Byte.toUnsignedInt(value[index]) != prefix[index]) return false;
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] value, String prefix) {
        byte[] bytes = prefix.getBytes(StandardCharsets.US_ASCII);
        if (value.length < bytes.length) return false;
        for (int index = 0; index < bytes.length; index++) {
            if (value[index] != bytes[index]) return false;
        }
        return true;
    }

    private static boolean looksLikeUtf8Text(byte[] value) {
        if (value.length == 0) return true;
        for (byte item : value) {
            int unsigned = Byte.toUnsignedInt(item);
            if (unsigned == 0) return false;
            if (unsigned < 0x20 && unsigned != '\t' && unsigned != '\n' && unsigned != '\r') return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value));
            return true;
        } catch (CharacterCodingException invalidUtf8) {
            return false;
        }
    }

    private static String stripUnsafeEdgeCharacters(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '.' || Character.isWhitespace(value.charAt(start)))) start++;
        while (end > start && (value.charAt(end - 1) == '.' || Character.isWhitespace(value.charAt(end - 1)))) end--;
        return value.substring(start, end).strip();
    }

    private static boolean isWindowsReservedBasename(String filename) {
        String basename = filename.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (Set.of("CON", "PRN", "AUX", "NUL").contains(basename)) return true;
        if (basename.length() == 4 && (basename.startsWith("COM") || basename.startsWith("LPT"))) {
            char number = basename.charAt(3);
            return number >= '1' && number <= '9';
        }
        return false;
    }
}
