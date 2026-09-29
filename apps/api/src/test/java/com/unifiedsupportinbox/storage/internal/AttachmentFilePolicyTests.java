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
