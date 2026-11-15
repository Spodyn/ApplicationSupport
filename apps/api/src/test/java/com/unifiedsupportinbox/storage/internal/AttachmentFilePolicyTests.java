package com.unifiedsupportinbox.storage.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AttachmentFilePolicyTests {

    private final AttachmentFilePolicy policy = new AttachmentFilePolicy();

    @Test
    void normalizesPathsControlsAndWindowsReservedNames() {
        assertThat(policy.normalizeFilename(" ../folder\\invoice.pdf ")).isEqualTo("_folder_invoice.pdf");
        assertThat(policy.normalizeFilename("CON.txt")).isEqualTo("_CON.txt");
        assertThat(policy.normalizeFilename("bad" + ((char) 0) + "name.txt")).isEqualTo("bad_name.txt");
    }

    @Test
    void acceptsSafePdfImageAndUtf8TextSignatures() throws Exception {
        assertAccepted("report.pdf", "application/pdf", pdf(), "application/pdf");
        assertAccepted("photo.png", "image/png", png(), "image/png");
        assertAccepted("notes.txt", "text/plain", "hello świata\n".getBytes(StandardCharsets.UTF_8), "text/plain");
        assertAccepted("payload.json", "application/json", "{\"safe\":true}".getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    @Test
    void rejectsDeclaredMimeMismatch() throws Exception {
        String detected = policy.detectContentType(new ByteArrayInputStream(pdf()));
        assertThatThrownBy(() -> policy.validate("report.pdf", "image/png", detected, pdf().length))
                .isInstanceOf(AttachmentPolicyViolationException.class)
                .extracting(error -> ((AttachmentPolicyViolationException) error).code())
                .isEqualTo(AttachmentPolicyViolationException.Code.MIME_MISMATCH);
    }

    @Test
    void rejectsDangerousDoubleExtensionEvenWhenMagicBytesAreSafe() throws Exception {
        String detected = policy.detectContentType(new ByteArrayInputStream(pdf()));
        assertThatThrownBy(() -> policy.validate("invoice.exe.pdf", "application/pdf", detected, pdf().length))
                .isInstanceOf(AttachmentPolicyViolationException.class)
                .extracting(error -> ((AttachmentPolicyViolationException) error).code())
                .isEqualTo(AttachmentPolicyViolationException.Code.BLOCKED_EXTENSION);
    }

    @Test
    void rejectsArchivesAndExecutableMagic() throws Exception {
        byte[] zip = new byte[] {0x50, 0x4b, 0x03, 0x04, 0x00};
        String detectedZip = policy.detectContentType(new ByteArrayInputStream(zip));
        assertThatThrownBy(() -> policy.validate("archive.zip", "application/zip", detectedZip, zip.length))
                .isInstanceOf(AttachmentPolicyViolationException.class)
                .extracting(error -> ((AttachmentPolicyViolationException) error).code())
                .isEqualTo(AttachmentPolicyViolationException.Code.BLOCKED_ARCHIVE);

        byte[] exe = new byte[] {(byte) 'M', (byte) 'Z', 0x00, 0x01};
        String detectedExe = policy.detectContentType(new ByteArrayInputStream(exe));
        assertThatThrownBy(() -> policy.validate("renamed.pdf", "application/pdf", detectedExe, exe.length))
                .isInstanceOf(AttachmentPolicyViolationException.class)
                .extracting(error -> ((AttachmentPolicyViolationException) error).code())
                .isEqualTo(AttachmentPolicyViolationException.Code.BLOCKED_EXECUTABLE);
    }

    @Test
    void rejectsOversizeAndUnknownBinaryContent() throws Exception {
        assertThatThrownBy(() -> policy.validate(
                        "big.pdf", "application/pdf", "application/pdf", AttachmentFilePolicy.MAX_FILE_BYTES + 1))
                .isInstanceOf(AttachmentPolicyViolationException.class)
                .extracting(error -> ((AttachmentPolicyViolationException) error).code())
                .isEqualTo(AttachmentPolicyViolationException.Code.TOO_LARGE);

        byte[] unknown = new byte[] {0x00, 0x01, 0x02, 0x03};
        String detected = policy.detectContentType(new ByteArrayInputStream(unknown));
        assertThatThrownBy(() -> policy.validate("file.data", "application/octet-stream", detected, unknown.length))
                .isInstanceOf(AttachmentPolicyViolationException.class)
                .extracting(error -> ((AttachmentPolicyViolationException) error).code())
                .isEqualTo(AttachmentPolicyViolationException.Code.UNSUPPORTED_TYPE);
    }

    @Test
    void validatesRealOfficePackagesAndRejectsGenericZipWithOfficeExtension() throws Exception {
        for (var office : OfficeAttachmentPackage.values()) {
            String extension = switch (office) {
                case WORD -> "docx";
                case EXCEL -> "xlsx";
                case POWERPOINT -> "pptx";
            };
            try (var content = getClass().getResourceAsStream("/attachments/preview-fixtures/sample." + extension)) {
                assertThat(policy.verifyOfficePackage("sample." + extension, content)).isEqualTo(office.mime());
            }
        }

        var archive = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(archive)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("notes.txt"));
            zip.write("plain archive".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> policy.verifyOfficePackage("renamed.docx",
                new ByteArrayInputStream(archive.toByteArray())))
                .isInstanceOf(AttachmentPolicyViolationException.class)
                .extracting(error -> ((AttachmentPolicyViolationException) error).code())
                .isEqualTo(AttachmentPolicyViolationException.Code.BLOCKED_ARCHIVE);
    }

    @Test
    void rejectsUnsafePartsWithinAnOtherwiseValidOfficePackage() throws Exception {
        for (String entry : java.util.List.of("word/vbaProject.bin", "word/embeddings/package.zip", "word/payload.exe",
                "../outside.xml")) {
            byte[] candidate = addOfficePart(entry);
            assertThatThrownBy(() -> policy.verifyOfficePackage("sample.docx", new ByteArrayInputStream(candidate)))
                    .as(entry)
                    .isInstanceOf(AttachmentPolicyViolationException.class);
        }
    }

    private byte[] addOfficePart(String entryName) throws Exception {
        var target = new java.io.ByteArrayOutputStream();
        try (var source = new java.util.zip.ZipInputStream(
                    getClass().getResourceAsStream("/attachments/preview-fixtures/sample.docx"));
                var archive = new java.util.zip.ZipOutputStream(target)) {
            for (var item = source.getNextEntry(); item != null; item = source.getNextEntry()) {
                archive.putNextEntry(new java.util.zip.ZipEntry(item.getName()));
                source.transferTo(archive);
                archive.closeEntry();
            }
            archive.putNextEntry(new java.util.zip.ZipEntry(entryName));
            archive.write("Synthetic blocked item".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return target.toByteArray();
    }

    @Test
    void recognizesModernRasterFormatsAndKeepsNonPreviewableTypesDownloadOnly() throws Exception {
        for (String extension : java.util.List.of("webp", "avif", "bmp", "tiff")) {
            try (var content = getClass().getResourceAsStream("/attachments/preview-fixtures/sample." + extension)) {
                byte[] bytes = content.readAllBytes();
                assertAccepted("sample." + extension, "image/" + extension, bytes, "image/" + extension);
            }
        }
        assertAccepted("sample.heic", "image/heic",
                new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0}, "image/heic");
        assertThat(AttachmentFilePolicy.supportsInlineImage("image/tiff")).isFalse();
        assertThat(AttachmentFilePolicy.supportsInlineImage("image/heic")).isFalse();
        assertThat(AttachmentFilePolicy.supportsInlineImage("image/svg+xml")).isFalse();
    }

    private void assertAccepted(String filename, String declared, byte[] bytes, String expectedDetected) throws Exception {
        String detected = policy.detectContentType(new ByteArrayInputStream(bytes));
        assertThat(detected).isEqualTo(expectedDetected);
        policy.validate(filename, declared, detected, bytes.length);
    }

    private static byte[] pdf() {
        return "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00};
    }
}
