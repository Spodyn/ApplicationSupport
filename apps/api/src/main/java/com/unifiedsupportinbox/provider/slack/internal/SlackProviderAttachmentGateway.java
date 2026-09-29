package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.provider.ProviderAttachmentGateway;
import com.unifiedsupportinbox.provider.ProviderAttachmentMetadata;
import com.unifiedsupportinbox.provider.SafeProviderFileDownloader;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class SlackProviderAttachmentGateway implements ProviderAttachmentGateway {

    private static final String BOT_TOKEN_CREDENTIAL_FILE = SlackMessageDeliveryProvider.BOT_TOKEN_CREDENTIAL_FILE;

    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final SlackAttachmentApiClient slack;
    private final SafeProviderFileDownloader downloader;

    SlackProviderAttachmentGateway(
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,
            SlackAttachmentApiClient slack) {
        this(integrations, secrets, slack, new SafeProviderFileDownloader());
    }

    SlackProviderAttachmentGateway(
            ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,
            SlackAttachmentApiClient slack,
            SafeProviderFileDownloader downloader) {
        this.integrations = java.util.Objects.requireNonNull(integrations, "integrations");
        this.secrets = java.util.Objects.requireNonNull(secrets, "secrets");
        this.slack = java.util.Objects.requireNonNull(slack, "slack");
        this.downloader = java.util.Objects.requireNonNull(downloader, "downloader");
    }

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.SLACK;
    }

    @Override
    public DownloadedAttachment download(DownloadRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required.");
        CredentialReference integration = credentialFor(request.integrationId());
        if (integration == null) {
            throw ProviderAttachmentException.authentication("SLACK_CREDENTIAL_REFERENCE_MISSING");
        }
        byte[] token = secrets.resolve(integration.secretRef(), BOT_TOKEN_CREDENTIAL_FILE).orElse(null);
        if (token == null) {
            throw ProviderAttachmentException.authentication("SLACK_BOT_TOKEN_MISSING");
        }
        try {
            SlackAttachmentApiClient.FileInfoResponse info = slack.fileInfo(token, request.providerFileId());
            requireSuccessful(info.statusCode(), info.ok(), info.errorCode(), info.retryAfter());
            if (info.providerFileId() == null || !request.providerFileId().equals(info.providerFileId())) {
                throw ProviderAttachmentException.validation("SLACK_FILE_ID_MISMATCH");
            }
            if (info.sizeBytes() < 0 || info.sizeBytes() > request.maxBytes()) {
                throw ProviderAttachmentException.validation("SLACK_FILE_SIZE_INVALID");
            }
            URI downloadUri = info.downloadUri();
            if (downloadUri == null) {
                throw ProviderAttachmentException.validation("SLACK_FILE_URL_MISSING");
            }

            SafeProviderFileDownloader.DownloadedFile downloaded;
            try {
                downloaded = downloader.download(SafeProviderFileDownloader.Provider.SLACK, downloadUri, token);
            } catch (RuntimeException failure) {
                throw mapRuntime("SLACK_FILE_DOWNLOAD_FAILED", failure);
            }
            byte[] bytes = downloaded.bytes();
            if (bytes.length != info.sizeBytes() || bytes.length > request.maxBytes()) {
                Arrays.fill(bytes, (byte) 0);
                throw ProviderAttachmentException.validation("SLACK_FILE_SIZE_MISMATCH");
            }
            String contentType = info.contentType() != null ? info.contentType() : downloaded.contentType();
            ProviderAttachmentMetadata metadata = new ProviderAttachmentMetadata(
                    info.providerFileId(), info.filename(), contentType, bytes.length);
            return new DownloadedAttachment(metadata, new ByteArrayInputStream(bytes));
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    @Override
    public UploadedAttachment upload(UploadRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required.");
        CredentialReference integration = credentialFor(request.integrationId());
        if (integration == null) {
            throw ProviderAttachmentException.authentication("SLACK_CREDENTIAL_REFERENCE_MISSING");
        }
        byte[] token = secrets.resolve(integration.secretRef(), BOT_TOKEN_CREDENTIAL_FILE).orElse(null);
        if (token == null) {
            throw ProviderAttachmentException.authentication("SLACK_BOT_TOKEN_MISSING");
        }
        byte[] bytes = null;
        try {
            bytes = readBounded(request);
            SlackAttachmentApiClient.UploadUrlResponse uploadUrl = slack.getUploadUrl(
                    token, request.metadata().filename(), bytes.length);
            requireSuccessful(
                    uploadUrl.statusCode(), uploadUrl.ok(), uploadUrl.errorCode(), uploadUrl.retryAfter());
            if (uploadUrl.providerFileId() == null || uploadUrl.uploadUri() == null) {
                throw ProviderAttachmentException.validation("SLACK_UPLOAD_URL_INVALID");
            }

            SlackAttachmentApiClient.UploadBytesResponse uploaded;
            try {
                uploaded = slack.uploadBytes(uploadUrl.uploadUri(), bytes);
            } catch (RuntimeException failure) {
                throw mapRuntime("SLACK_UPLOAD_BYTES_FAILED", failure);
            }
            if (!uploaded.successful()) {
                requireSuccessful(uploaded.statusCode(), false, uploaded.errorCode(), uploaded.retryAfter());
            }

            SlackAttachmentApiClient.CompleteUploadResponse completed = slack.completeUpload(
                    token,
                    uploadUrl.providerFileId(),
                    request.metadata().filename(),
                    request.externalChannelId(),
                    request.externalThreadKey());
            requireSuccessful(
                    completed.statusCode(), completed.ok(), completed.errorCode(), completed.retryAfter());
            String providerFileId = completed.providerFileId() != null
                    ? completed.providerFileId()
                    : uploadUrl.providerFileId();
            return new UploadedAttachment(providerFileId, null);
        } finally {
            Arrays.fill(token, (byte) 0);
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private byte[] readBounded(UploadRequest request) {
        long expected = request.metadata().sizeBytes();
        if (expected > MAX_FILE_BYTES) {
            throw ProviderAttachmentException.validation("SLACK_FILE_TOO_LARGE");
        }
        try (InputStream input = request.content().open()) {
            byte[] bytes = input.readNBytes((int) MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                Arrays.fill(bytes, (byte) 0);
                throw ProviderAttachmentException.validation("SLACK_FILE_TOO_LARGE");
            }
            if (bytes.length != expected) {
                Arrays.fill(bytes, (byte) 0);
                throw ProviderAttachmentException.validation("SLACK_UPLOAD_SIZE_MISMATCH");
            }
            return bytes;
        } catch (IOException failure) {
            throw ProviderAttachmentException.unavailable("SLACK_UPLOAD_CONTENT_READ_FAILED", failure);
        }
    }

    private CredentialReference credentialFor(java.util.UUID integrationId) {
        List<CredentialReference> matches = integrations.findForProvider(IntegrationProvider.SLACK).stream()
                .filter(reference -> integrationId.equals(reference.integrationId()))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static void requireSuccessful(
            int statusCode,
            boolean ok,
            String errorCode,
            Duration retryAfter) {
        if (statusCode == 429 || "rate_limited".equals(errorCode) || "ratelimited".equals(errorCode)) {
            throw ProviderAttachmentException.rateLimited(
                    SlackDeliveryErrorClassifier.normalizedApiError(errorCode), retryAfter);
        }
        if (statusCode < 200 || statusCode >= 300) {
            String code = "SLACK_HTTP_" + statusCode;
            if (SlackDeliveryErrorClassifier.isTransientHttpStatus(statusCode)) {
                throw ProviderAttachmentException.unavailable(code, null);
            }
            if (statusCode == 401 || statusCode == 403) {
                throw ProviderAttachmentException.authentication(code);
            }
            throw ProviderAttachmentException.validation(code);
        }
        if (ok) return;

        String normalized = SlackDeliveryErrorClassifier.normalizedApiError(errorCode);
        if (SlackDeliveryErrorClassifier.isTransientApiError(errorCode)) {
            throw ProviderAttachmentException.unavailable(normalized, null);
        }
        if ("file_not_found".equals(errorCode)) {
            throw ProviderAttachmentException.notFound(normalized);
        }
        if (isAuthenticationError(errorCode)) {
            throw ProviderAttachmentException.authentication(normalized);
        }
        throw ProviderAttachmentException.validation(normalized);
    }

    private static boolean isAuthenticationError(String errorCode) {
        return "invalid_auth".equals(errorCode)
                || "not_authed".equals(errorCode)
                || "account_inactive".equals(errorCode)
                || "token_revoked".equals(errorCode)
                || "missing_scope".equals(errorCode)
                || "no_permission".equals(errorCode);
    }

    private static ProviderAttachmentException mapRuntime(String code, RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof HttpTimeoutException) {
                return ProviderAttachmentException.timeout(code, failure);
            }
            current = current.getCause();
        }
        return ProviderAttachmentException.unavailable(code, failure);
    }
}
