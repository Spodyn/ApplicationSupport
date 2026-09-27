package com.unifiedsupportinbox.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderAttachmentGatewayContractTests {

    @Test
    void fakeProviderDownloadsAndUploadsWithoutProviderUrlsLeakingIntoTheContract() throws Exception {
        FakeGateway gateway = new FakeGateway();
        UUID integrationId = UUID.randomUUID();

        try (ProviderAttachmentGateway.DownloadedAttachment downloaded = gateway.download(
                new ProviderAttachmentGateway.DownloadRequest(integrationId, " provider-file-1 ", 1024))) {
            assertThat(downloaded.metadata().providerFileId()).isEqualTo("provider-file-1");
            assertThat(downloaded.metadata().filename()).isEqualTo("folder_secret.txt");
            assertThat(downloaded.metadata().contentType()).isEqualTo("text/plain");
            assertThat(downloaded.content().readAllBytes()).isEqualTo("download-body".getBytes(StandardCharsets.UTF_8));
        }

        AtomicInteger opened = new AtomicInteger();
        ProviderAttachmentGateway.UploadedAttachment uploaded = gateway.upload(
                new ProviderAttachmentGateway.UploadRequest(
                        integrationId,
                        " C123 ",
                        " 1712000000.000001 ",
                        new ProviderAttachmentUploadMetadata(" report.PDF ", " Application/PDF; charset=binary ", 6),
                        () -> {
                            opened.incrementAndGet();
                            return new ByteArrayInputStream("upload".getBytes(StandardCharsets.UTF_8));
                        },
                        " message-attachment-1 "));

        assertThat(gateway.lastUpload.metadata().filename()).isEqualTo("report.PDF");
        assertThat(gateway.lastUpload.metadata().contentType()).isEqualTo("application/pdf");
        assertThat(gateway.lastUpload.externalChannelId()).isEqualTo("C123");
        assertThat(uploaded.providerFileId()).isEqualTo("uploaded-provider-file");
        assertThat(opened).hasValue(1);

        assertThat(ProviderAttachmentGateway.UploadRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("url", "providerUrl", "downloadUrl", "authorization", "token");
        assertThat(ProviderAttachmentMetadata.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("url", "providerUrl", "downloadUrl", "authorization", "token");
    }

    @Test
    void metadataNormalizationRemovesPathSeparatorsAndFallsBackForInvalidMediaType() {
        ProviderAttachmentMetadata metadata = new ProviderAttachmentMetadata(
                " F123 ", "../folder\\invoice.csv\n", " invalid-media-type ", 17);

        assertThat(metadata.providerFileId()).isEqualTo("F123");
        assertThat(metadata.filename()).doesNotContain("/", "\\", "\n");
        assertThat(metadata.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void contractRejectsOversizedTransferBeforeProviderCodeRuns() {
        assertThatThrownBy(() -> new ProviderAttachmentGateway.DownloadRequest(
                UUID.randomUUID(), "F1", ProviderAttachmentGateway.MAX_FILE_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ProviderAttachmentUploadMetadata(
                "oversized.bin", "application/octet-stream", ProviderAttachmentGateway.MAX_FILE_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerFailureTaxonomyDistinguishesRetryableAndPermanentFailures() {
        IOException timeoutCause = new IOException("fixture timeout");
        ProviderAttachmentException timeout = ProviderAttachmentException.timeout("request_timeout", timeoutCause);
        ProviderAttachmentException redirect = ProviderAttachmentException.unsafeRedirect("redirect_private_address");
        ProviderAttachmentException missing = ProviderAttachmentException.notFound("file_not_found");
        ProviderAttachmentException auth = ProviderAttachmentException.authentication("invalid_auth");
        ProviderAttachmentException rateLimited = ProviderAttachmentException.rateLimited(
                "rate_limited", Duration.ofSeconds(45));

        assertThat(timeout.retryable()).isTrue();
        assertThat(timeout.getCause()).isSameAs(timeoutCause);
        assertThat(timeout.getMessage()).doesNotContain("fixture timeout");
        assertThat(rateLimited.retryable()).isTrue();
        assertThat(rateLimited.retryAfter()).isEqualTo(Duration.ofSeconds(45));

        assertThat(redirect.kind()).isEqualTo(ProviderAttachmentException.FailureKind.UNSAFE_REDIRECT);
        assertThat(redirect.retryable()).isFalse();
        assertThat(missing.kind()).isEqualTo(ProviderAttachmentException.FailureKind.NOT_FOUND);
        assertThat(missing.retryable()).isFalse();
        assertThat(auth.kind()).isEqualTo(ProviderAttachmentException.FailureKind.AUTHENTICATION);
        assertThat(auth.retryable()).isFalse();
    }

    private static final class FakeGateway implements ProviderAttachmentGateway {
        private UploadRequest lastUpload;

        @Override
        public IntegrationProvider provider() {
            return IntegrationProvider.SLACK;
        }

        @Override
        public DownloadedAttachment download(DownloadRequest request) {
            ProviderAttachmentMetadata metadata = new ProviderAttachmentMetadata(
                    request.providerFileId(), "folder/secret.txt", " Text/Plain; charset=UTF-8 ", 13);
            return new DownloadedAttachment(
                    metadata,
                    new ByteArrayInputStream("download-body".getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public UploadedAttachment upload(UploadRequest request) {
            lastUpload = request;
            try (var stream = request.content().open()) {
                assertThat(stream.readAllBytes()).hasSize((int) request.metadata().sizeBytes());
            } catch (IOException exception) {
                throw ProviderAttachmentException.unavailable("local_content_unavailable", exception);
            }
            return new UploadedAttachment("uploaded-provider-file", "provider-message-ref");
        }
    }
}
