package com.unifiedsupportinbox.provider;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Provider-neutral boundary for attachment transfer.
 *
 * <p>Business/storage modules pass stable provider file identifiers and local
 * conversation identifiers through this contract. Provider-authenticated URLs,
 * credentials and SDK response objects remain inside provider adapters and must
 * never be persisted as attachment source-of-truth or exposed to the browser.</p>
 */
public interface ProviderAttachmentGateway {

    long MAX_FILE_BYTES = 25L * 1024L * 1024L;
    Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    IntegrationProvider provider();

    DownloadedAttachment download(DownloadRequest request);

    UploadedAttachment upload(UploadRequest request);

    record DownloadRequest(
            UUID integrationId,
            String providerFileId,
            long maxBytes) {

        public DownloadRequest {
            Objects.requireNonNull(integrationId, "integrationId");
            providerFileId = ProviderAttachmentMetadata.requireIdentifier(providerFileId, "providerFileId");
            if (maxBytes < 1 || maxBytes > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("maxBytes must be between 1 and 25 MiB.");
            }
        }
    }

    record UploadRequest(
            UUID integrationId,
            String externalChannelId,
            String externalThreadKey,
            ProviderAttachmentUploadMetadata metadata,
            ContentSource content,
            String idempotencyKey) {

        public UploadRequest {
            Objects.requireNonNull(integrationId, "integrationId");
            externalChannelId = ProviderAttachmentMetadata.requireIdentifier(externalChannelId, "externalChannelId");
            if (externalThreadKey != null) {
                externalThreadKey = ProviderAttachmentMetadata.requireIdentifier(externalThreadKey, "externalThreadKey");
            }
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(content, "content");
            idempotencyKey = ProviderAttachmentMetadata.requireIdentifier(idempotencyKey, "idempotencyKey");
        }
    }

    /**
     * Repeatable content source. Provider implementations may reopen the local
     * object for a bounded retry; every returned stream is owned by the caller.
     */
    @FunctionalInterface
    interface ContentSource {
        InputStream open() throws IOException;
    }

    /** Download handle whose stream must be closed by the application pipeline. */
    final class DownloadedAttachment implements AutoCloseable {
        private final ProviderAttachmentMetadata metadata;
        private final InputStream content;

        public DownloadedAttachment(ProviderAttachmentMetadata metadata, InputStream content) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.content = Objects.requireNonNull(content, "content");
        }

        public ProviderAttachmentMetadata metadata() {
            return metadata;
        }

        public InputStream content() {
            return content;
        }

        @Override
        public void close() throws IOException {
            content.close();
        }
    }

    record UploadedAttachment(
            String providerFileId,
            String externalMessageId) {

        public UploadedAttachment {
            providerFileId = ProviderAttachmentMetadata.requireIdentifier(providerFileId, "providerFileId");
            if (externalMessageId != null) {
                externalMessageId = ProviderAttachmentMetadata.requireIdentifier(externalMessageId, "externalMessageId");
            }
        }
    }
}
