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

    private static final Map<String, Set<String>> SAFE_EXTENSIONS = Map.ofEntries(
            Map.entry("application/pdf", Set.of("pdf")),
            Map.entry("image/png", Set.of("png")),
            Map.entry("image/jpeg", Set.of("jpg", "jpeg")),
            Map.entry("image/gif", Set.of("gif")),
            Map.entry("image/webp", Set.of("webp")),
            Map.entry("image/avif", Set.of("avif")),
            Map.entry("image/bmp", Set.of("bmp")),
            Map.entry("image/tiff", Set.of("tif", "tiff")),
            Map.entry("image/heic", Set.of("heic", "heif", "hif")),
            Map.entry("image/heif", Set.of("heif", "hif")),
            Map.entry(OfficeAttachmentPackage.WORD.mime(), Set.of("docx")),
            Map.entry(OfficeAttachmentPackage.EXCEL.mime(), Set.of("xlsx")),
            Map.entry(OfficeAttachmentPackage.POWERPOINT.mime(), Set.of("pptx")),
            Map.entry("text/plain", Set.of("txt", "csv", "json", "md", "log")));

    private static final Set<String> INLINE_IMAGES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/bmp");

    static boolean supportsInlineImage(String type) {
        return type != null && INLINE_IMAGES.contains(type);
    }

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
        if (sample.length >= 16 && startsWithAscii(sample, "RIFF") && asciiAt(sample, 8, "WEBP")
                && (asciiAt(sample, 12, "VP8 ") || asciiAt(sample, 12, "VP8L") || asciiAt(sample, 12, "VP8X"))) {
            return "image/webp";
        }
        if (startsWith(sample, new int[] {0x49, 0x49, 0x2a, 0})
                || startsWith(sample, new int[] {0x4d, 0x4d, 0, 0x2a})
                || startsWith(sample, new int[] {0x49, 0x49, 0x2b, 0})
                || startsWith(sample, new int[] {0x4d, 0x4d, 0, 0x2b})) return "image/tiff";
        if (sample.length >= 26 && startsWithAscii(sample, "BM")
                && ByteBuffer.wrap(sample, 10, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt() >= 26) {
            return "image/bmp";
        }
        String imageBrand = imageContainerType(sample);
        if (imageBrand != null) return imageBrand;
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
        if ("application/zip".equals(detectedContentType)) {
            var office = OfficeAttachmentPackage.forExtension(finalExtension(filename));
            if (office != null) {
                String declared = normalizeContentType(declaredContentType);
                if (declaredCompatible(declared, office.mime()) || "application/zip".equals(declared)) return;
                throw new AttachmentPolicyViolationException(AttachmentPolicyViolationException.Code.MIME_MISMATCH);
            }
        }
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
        if ("image/x-ms-bmp".equals(result) || "image/x-bmp".equals(result)) return "image/bmp";
        if ("image/x-tiff".equals(result)) return "image/tiff";
        return result;
    }

    private static boolean declaredCompatible(String declared, String detected) {
        if ("application/octet-stream".equals(declared)) return true;
        if (declared.equals(detected)) return true;
        if ("application/zip".equals(declared) && OfficeAttachmentPackage.isOfficeMime(detected)) return true;
        if ("image/heif".equals(declared) && "image/heic".equals(detected)) return true;
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

    String verifyOfficePackage(String filename, InputStream content) throws IOException, AttachmentPolicyViolationException {
        var office = OfficeAttachmentPackage.forExtension(finalExtension(filename));
        if (office == null) {
            throw new AttachmentPolicyViolationException(AttachmentPolicyViolationException.Code.BLOCKED_ARCHIVE);
        }
        office.validate(content);
        return office.mime();
    }

    private static String imageContainerType(byte[] sample) {
        if (sample.length < 16 || !asciiAt(sample, 4, "ftyp")) return null;
        int boxLength = ByteBuffer.wrap(sample).getInt();
        if (boxLength < 16 || boxLength > sample.length || boxLength % 4 != 0) return null;
        boolean heic = false;
        boolean heif = false;
        for (int offset = 8; offset + 4 <= boxLength; offset += offset == 8 ? 8 : 4) {
            String brand = new String(sample, offset, 4, StandardCharsets.US_ASCII);
            if (Set.of("avif", "avis").contains(brand)) return "image/avif";
            heic |= Set.of("heic", "heix", "hevc", "hevx").contains(brand);
            heif |= Set.of("mif1", "msf1").contains(brand);
        }
        if (heic) return "image/heic";
        return heif ? "image/heif" : null;
    }

    private static boolean asciiAt(byte[] value, int offset, String expected) {
        return new String(value, offset, expected.length(), StandardCharsets.US_ASCII).equals(expected);
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
