package com.unifiedsupportinbox.storage.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.storage.AttachmentMalwareScanner;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import com.unifiedsupportinbox.storage.AttachmentQuarantinedException;
import com.unifiedsupportinbox.storage.AttachmentScanException;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultAttachmentSecurityServiceTests {

    @Test
    void cleanFileBecomesDownloadableWithDetectedMetadata() throws Exception {
        Fixture fixture = fixture("safe.pdf", "application/pdf", "%PDF-1.7\nhello".getBytes(StandardCharsets.US_ASCII));
        DefaultAttachmentSecurityService service = fixture.service(content -> AttachmentMalwareScanner.ScanResult.CLEAN);

        AttachmentMetadata result = service.scan(fixture.id());

        assertThat(result.scanStatus()).isEqualTo(AttachmentScanStatus.CLEAN);
        assertThat(result.detectedContentType()).isEqualTo("application/pdf");
        assertThat(service.requireClean(fixture.id())).isEqualTo(result);
    }

    @Test
    void eicarFixtureIsQuarantinedAsInfected() throws Exception {
        byte[] eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                .getBytes(StandardCharsets.US_ASCII);
        Fixture fixture = fixture("eicar.txt", "text/plain", eicar);
        DefaultAttachmentSecurityService service = fixture.service(content -> {
            String body = new String(content.readAllBytes(), StandardCharsets.US_ASCII);
            return body.contains("EICAR-STANDARD-ANTIVIRUS-TEST-FILE")
                    ? AttachmentMalwareScanner.ScanResult.INFECTED
                    : AttachmentMalwareScanner.ScanResult.CLEAN;
        });

        AttachmentMetadata result = service.scan(fixture.id());

        assertThat(result.scanStatus()).isEqualTo(AttachmentScanStatus.INFECTED);
        assertThatThrownBy(() -> service.requireClean(fixture.id()))
                .isInstanceOf(AttachmentQuarantinedException.class);
    }

    @Test
    void scannerTimeoutFailsClosedWithStableCode() throws Exception {
        Fixture fixture = fixture("safe.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        DefaultAttachmentSecurityService service = fixture.service(content -> {
            throw new AttachmentScanException(AttachmentScanException.Code.SCANNER_TIMEOUT);
        });

        AttachmentMetadata result = service.scan(fixture.id());

        assertThat(result.scanStatus()).isEqualTo(AttachmentScanStatus.ERROR);
        assertThat(result.scanError()).isEqualTo("SCANNER_TIMEOUT");
        assertThatThrownBy(() -> service.requireClean(fixture.id()))
                .isInstanceOf(AttachmentQuarantinedException.class);
    }

    @Test
    void permanentFilePolicyFailureNeverCallsMalwareEngine() throws Exception {
        Fixture fixture = fixture("invoice.exe.pdf", "application/pdf", "%PDF-1.7\nhello".getBytes(StandardCharsets.US_ASCII));
        AttachmentMalwareScanner scanner = mock(AttachmentMalwareScanner.class);
        DefaultAttachmentSecurityService service = fixture.service(scanner);

        AttachmentMetadata result = service.scan(fixture.id());

        assertThat(result.scanStatus()).isEqualTo(AttachmentScanStatus.ERROR);
        assertThat(result.scanError()).isEqualTo("POLICY_BLOCKED_EXTENSION");
        verify(scanner, org.mockito.Mockito.never()).scan(any());
    }

    @Test
    void activeScanCannotBeClaimedTwice() {
        UUID id = UUID.randomUUID();
        AttachmentMetadataCatalog metadata = mock(AttachmentMetadataCatalog.class);
        AttachmentObjectStorage objects = mock(AttachmentObjectStorage.class);
        when(metadata.claimForScan(id)).thenReturn(Optional.empty());
        when(metadata.findById(id)).thenReturn(Optional.of(metadata(id, "file.txt", "text/plain", 4, AttachmentScanStatus.SCANNING, null)));
        DefaultAttachmentSecurityService service = new DefaultAttachmentSecurityService(
                metadata, objects, new AttachmentFilePolicy(), content -> AttachmentMalwareScanner.ScanResult.CLEAN);

        assertThatThrownBy(() -> service.scan(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
    }

    private static Fixture fixture(String filename, String contentType, byte[] bytes) {
        UUID id = UUID.randomUUID();
        AttachmentMetadataCatalog metadata = mock(AttachmentMetadataCatalog.class);
        AttachmentObjectStorage objects = mock(AttachmentObjectStorage.class);
        AttachmentMetadata claimed = metadata(id, filename, contentType, bytes.length, AttachmentScanStatus.SCANNING, null);
        when(metadata.claimForScan(id)).thenReturn(Optional.of(claimed));
        when(objects.open(claimed.storageKey())).thenReturn(new ByteArrayInputStream(bytes));
        when(metadata.completeScan(eq(id), anyString(), any(), any(), any())).thenAnswer(invocation ->
                metadata(
                        id,
                        invocation.getArgument(1, String.class),
                        contentType,
                        bytes.length,
                        invocation.getArgument(3, AttachmentScanStatus.class),
                        invocation.getArgument(4, String.class),
                        invocation.getArgument(2, String.class)));
        when(metadata.findById(id)).thenAnswer(invocation -> Optional.ofNullable(null));
        return new Fixture(id, metadata, objects, bytes);
    }

    private static AttachmentMetadata metadata(
            UUID id,
            String filename,
            String contentType,
            long size,
            AttachmentScanStatus status,
            String scanError) {
        return metadata(id, filename, contentType, size, status, scanError, null);
    }

    private static AttachmentMetadata metadata(
            UUID id,
            String filename,
            String contentType,
            long size,
            AttachmentScanStatus status,
            String scanError,
            String detectedContentType) {
        return new AttachmentMetadata(
                id,
                null,
                "attachments/2026/09/" + id,
                filename,
                contentType,
                detectedContentType,
                size,
                "a".repeat(64),
                status,
                scanError,
                null,
                Instant.parse("2026-09-28T08:00:00Z"));
    }

    private record Fixture(
            UUID id,
            AttachmentMetadataCatalog metadata,
            AttachmentObjectStorage objects,
            byte[] bytes) {

        DefaultAttachmentSecurityService service(AttachmentMalwareScanner scanner) {
            when(metadata.findById(id)).thenAnswer(invocation -> {
                // Return the last completed state when requireClean is called after scan.
                var captor = org.mockito.ArgumentCaptor.forClass(AttachmentScanStatus.class);
                return Optional.empty();
            });
            return new DefaultAttachmentSecurityService(metadata, objects, new AttachmentFilePolicy(), scanner) {
                @Override
                public AttachmentMetadata requireClean(UUID attachmentId) {
                    // Unit tests assert the guard separately through a catalog configured by the caller.
                    return super.requireClean(attachmentId);
                }
            };
        }
    }
}
