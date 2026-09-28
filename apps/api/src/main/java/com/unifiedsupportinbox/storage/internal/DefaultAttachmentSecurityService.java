package com.unifiedsupportinbox.storage.internal;

import com.unifiedsupportinbox.storage.AttachmentMalwareScanner;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentObjectNotFoundException;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import com.unifiedsupportinbox.storage.AttachmentQuarantinedException;
import com.unifiedsupportinbox.storage.AttachmentScanException;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import com.unifiedsupportinbox.storage.AttachmentSecurityService;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class DefaultAttachmentSecurityService implements AttachmentSecurityService {

    private final AttachmentMetadataCatalog metadata;
    private final AttachmentObjectStorage objects;
    private final AttachmentFilePolicy policy;
    private final AttachmentMalwareScanner malwareScanner;

    DefaultAttachmentSecurityService(
            AttachmentMetadataCatalog metadata,
            AttachmentObjectStorage objects,
            AttachmentFilePolicy policy,
            AttachmentMalwareScanner malwareScanner) {
        this.metadata = metadata;
        this.objects = objects;
        this.policy = policy;
        this.malwareScanner = malwareScanner;
    }

    @Override
    public AttachmentMetadata scan(UUID attachmentId) {
        Objects.requireNonNull(attachmentId, "attachmentId");
        AttachmentMetadata claimed = metadata.claimForScan(attachmentId).orElse(null);
        if (claimed == null) {
            AttachmentMetadata current = requireExisting(attachmentId);
            if (current.scanStatus() == AttachmentScanStatus.CLEAN
                    || current.scanStatus() == AttachmentScanStatus.INFECTED) {
                return current;
            }
            if (current.scanStatus() == AttachmentScanStatus.SCANNING) {
                throw new IllegalStateException("Attachment scan is already in progress.");
            }
            throw new IllegalStateException("Attachment could not be claimed for scanning.");
        }

        String normalizedFilename = policy.normalizeFilename(claimed.originalFilename());
        String detectedContentType = claimed.detectedContentType();
        InputStream raw;
        try {
            raw = objects.open(claimed.storageKey());
        } catch (AttachmentObjectNotFoundException missing) {
            return fail(claimed.id(), normalizedFilename, detectedContentType, "OBJECT_MISSING");
        } catch (RuntimeException unavailable) {
            return fail(claimed.id(), normalizedFilename, detectedContentType, "OBJECT_READ_ERROR");
        }

        try (BufferedInputStream content = new BufferedInputStream(raw)) {
            content.mark(AttachmentFilePolicy.SNIFF_BYTES + 1);
            detectedContentType = policy.detectContentType(content);
            content.reset();
            policy.validate(
                    normalizedFilename,
                    claimed.contentType(),
                    detectedContentType,
                    claimed.sizeBytes());

            AttachmentMalwareScanner.ScanResult result = malwareScanner.scan(content);
            AttachmentScanStatus finalStatus = result == AttachmentMalwareScanner.ScanResult.CLEAN
                    ? AttachmentScanStatus.CLEAN
                    : AttachmentScanStatus.INFECTED;
            return metadata.completeScan(
                    claimed.id(), normalizedFilename, detectedContentType, finalStatus, null);
        } catch (AttachmentPolicyViolationException violation) {
            return fail(
                    claimed.id(),
                    normalizedFilename,
                    detectedContentType,
                    "POLICY_" + violation.code().name());
        } catch (AttachmentScanException scanFailure) {
            return fail(
                    claimed.id(),
                    normalizedFilename,
                    detectedContentType,
                    scanFailure.code().name());
        } catch (IOException readFailure) {
            return fail(claimed.id(), normalizedFilename, detectedContentType, "SCAN_IO_ERROR");
        }
    }

    @Override
    public AttachmentMetadata requireClean(UUID attachmentId) {
        Objects.requireNonNull(attachmentId, "attachmentId");
        AttachmentMetadata current = requireExisting(attachmentId);
        if (current.scanStatus() != AttachmentScanStatus.CLEAN) {
            throw new AttachmentQuarantinedException();
        }
        return current;
    }

    private AttachmentMetadata requireExisting(UUID attachmentId) {
        return metadata.findById(attachmentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown attachmentId."));
    }

    private AttachmentMetadata fail(
            UUID attachmentId,
            String normalizedFilename,
            String detectedContentType,
            String stableCode) {
        return metadata.completeScan(
                attachmentId,
                normalizedFilename,
                detectedContentType,
                AttachmentScanStatus.ERROR,
                stableCode);
    }
}
