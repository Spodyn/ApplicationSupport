package com.unifiedsupportinbox.provider.slack.internal;

import java.net.URI;
import java.time.Duration;

/** Low-level Slack file API boundary used by the provider attachment adapter. */
interface SlackAttachmentApiClient {

    FileInfoResponse fileInfo(byte[] botToken, String providerFileId);

    UploadUrlResponse getUploadUrl(byte[] botToken, String filename, long sizeBytes);

    UploadBytesResponse uploadBytes(URI uploadUrl, byte[] content);

    CompleteUploadResponse completeUpload(
            byte[] botToken,
            String providerFileId,
            String title,
            String channelId,
            String threadTs);

    record FileInfoResponse(
            int statusCode,
            boolean ok,
            String providerFileId,
            String filename,
            String contentType,
            long sizeBytes,
            URI downloadUri,
            String errorCode,
            Duration retryAfter) {
    }

    record UploadUrlResponse(
            int statusCode,
            boolean ok,
            String providerFileId,
            URI uploadUri,
            String errorCode,
            Duration retryAfter) {
    }

    record UploadBytesResponse(
            int statusCode,
            String errorCode,
            Duration retryAfter) {

        boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    record CompleteUploadResponse(
            int statusCode,
            boolean ok,
            String providerFileId,
            String errorCode,
            Duration retryAfter) {
    }
}
