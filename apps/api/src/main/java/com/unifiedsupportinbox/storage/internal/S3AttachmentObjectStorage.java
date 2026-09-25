package com.unifiedsupportinbox.storage.internal;

import com.unifiedsupportinbox.storage.AttachmentObjectNotFoundException;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import java.io.InputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

final class S3AttachmentObjectStorage implements AttachmentObjectStorage {

    private final S3Client s3;
    private final String bucket;

    S3AttachmentObjectStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("attachments bucket must not be blank.");
        }
        this.bucket = bucket;
    }

    @Override
    public void put(String storageKey, InputStream content, long contentLength, String contentType) {
        String key = requireKey(storageKey);
        if (content == null) throw new IllegalArgumentException("content must not be null.");
        if (contentLength < 0) throw new IllegalArgumentException("contentLength must not be negative.");
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentLength(contentLength);
        if (contentType != null && !contentType.isBlank()) {
            request.contentType(contentType);
        }
        s3.putObject(request.build(), RequestBody.fromInputStream(content, contentLength));
    }

    @Override
    public InputStream open(String storageKey) {
        String key = requireKey(storageKey);
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new AttachmentObjectNotFoundException(key);
            }
            throw exception;
        }
    }

    @Override
    public boolean exists(String storageKey) {
        String key = requireKey(storageKey);
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) return false;
            throw exception;
        }
    }

    @Override
    public void delete(String storageKey) {
        s3.deleteObject(builder -> builder.bucket(bucket).key(requireKey(storageKey)));
    }

    private static String requireKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank.");
        }
        if (storageKey.length() > 512
                || !storageKey.startsWith("attachments/")
                || storageKey.startsWith("/")
                || storageKey.contains("..")
                || storageKey.contains("\\")) {
            throw new IllegalArgumentException("storageKey must be an internal attachment object key.");
        }
        return storageKey;
    }
}
