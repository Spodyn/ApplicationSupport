package com.unifiedsupportinbox.provider;

/** Sanitized local metadata supplied to a provider before it assigns a provider file id. */
public record ProviderAttachmentUploadMetadata(
        String filename,
        String contentType,
        long sizeBytes) {

    public ProviderAttachmentUploadMetadata {
        filename = ProviderAttachmentMetadata.normalizeFilename(filename);
        contentType = ProviderAttachmentMetadata.normalizeContentType(contentType);
        if (sizeBytes < 0 || sizeBytes > ProviderAttachmentGateway.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("sizeBytes must be between 0 and 25 MiB.");
        }
    }
}
