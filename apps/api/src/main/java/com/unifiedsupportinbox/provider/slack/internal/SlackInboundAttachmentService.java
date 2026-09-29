package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.provider.ProviderAttachmentGateway;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import com.unifiedsupportinbox.storage.AttachmentSecurityService;
import com.unifiedsupportinbox.storage.AttachmentStorageKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class SlackInboundAttachmentService {

    private final SlackProviderAttachmentGateway gateway;
    private final AttachmentMetadataCatalog metadata;
    private final ObjectProvider<AttachmentObjectStorage> objectStorage;
    private final ObjectProvider<AttachmentSecurityService> security;

    SlackInboundAttachmentService(
            SlackProviderAttachmentGateway gateway,
            AttachmentMetadataCatalog metadata,
            ObjectProvider<AttachmentObjectStorage> objectStorage,
            ObjectProvider<AttachmentSecurityService> security) {
        this.gateway = gateway;
        this.metadata = metadata;
        this.objectStorage = objectStorage;
        this.security = security;
    }

    void ingest(
            UUID integrationId,
            InboundMessageCommandHandler.Result persisted,
            JsonNode slackEvent) {
        if (persisted == null || slackEvent == null) return;
        JsonNode files = slackEvent.get("files");
        if (files == null || !files.isArray() || files.size() == 0) return;

        AttachmentObjectStorage objects = objectStorage.getIfUnique();
        AttachmentSecurityService scanner = security.getIfUnique();
        if (objects == null || scanner == null) {
            throw SlackInboundProcessingException.transientFailure(
                    "ATTACHMENT_STORAGE_UNAVAILABLE",
                    "Attachment storage or scanning service is unavailable.");
        }

        for (JsonNode file : files) {
            String providerFileId = providerFileId(file);
            if (alreadyStored(persisted.messageId(), providerFileId)) continue;
            ingestOne(integrationId, persisted.messageId(), providerFileId, objects, scanner);
        }
    }

    private void ingestOne(
            UUID integrationId,
            UUID messageId,
            String providerFileId,
            AttachmentObjectStorage objects,
            AttachmentSecurityService scanner) {
        try (ProviderAttachmentGateway.DownloadedAttachment downloaded = gateway.download(
                new ProviderAttachmentGateway.DownloadRequest(
                        integrationId,
                        providerFileId,
                        ProviderAttachmentGateway.MAX_FILE_BYTES))) {
            byte[] bytes = readExact(downloaded);
            String storageKey = AttachmentStorageKey.generate();
            boolean stored = false;
            try {
                objects.put(
                        storageKey,
                        new ByteArrayInputStream(bytes),
                        bytes.length,
                        downloaded.metadata().contentType());
                stored = true;
                AttachmentMetadata created = metadata.create(new AttachmentMetadataCatalog.CreateAttachment(
                        messageId,
                        storageKey,
                        downloaded.metadata().filename(),
                        downloaded.metadata().contentType(),
                        null,
                        bytes.length,
                        sha256(bytes),
                        AttachmentScanStatus.PENDING,
                        null,
                        providerFileId));
                scanner.scan(created.id());
            } catch (RuntimeException failure) {
                if (stored && !hasMetadata(messageId, providerFileId)) {
                    try {
                        objects.delete(storageKey);
                    } catch (RuntimeException ignored) {
                        // The orphan cleanup task remains the final recovery path if storage is unavailable here.
                    }
                }
                throw failure;
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        } catch (ProviderAttachmentException failure) {
            throw inboundFailure(failure);
        } catch (IOException failure) {
            throw SlackInboundProcessingException.transientFailure(
                    "SLACK_ATTACHMENT_STREAM_FAILED",
                    "Slack attachment content could not be read.");
        }
    }

    private boolean alreadyStored(UUID messageId, String providerFileId) {
        return hasMetadata(messageId, providerFileId);
    }

    private boolean hasMetadata(UUID messageId, String providerFileId) {
        List<AttachmentMetadata> existing = metadata.findByMessageId(messageId);
        return existing.stream().anyMatch(value -> providerFileId.equals(value.providerFileId()));
    }

    private static byte[] readExact(ProviderAttachmentGateway.DownloadedAttachment downloaded) throws IOException {
        long expected = downloaded.metadata().sizeBytes();
        if (expected < 0 || expected > ProviderAttachmentGateway.MAX_FILE_BYTES) {
            throw ProviderAttachmentException.validation("SLACK_FILE_SIZE_INVALID");
        }
        byte[] bytes = downloaded.content().readNBytes((int) ProviderAttachmentGateway.MAX_FILE_BYTES + 1);
        if (bytes.length > ProviderAttachmentGateway.MAX_FILE_BYTES || bytes.length != expected) {
            java.util.Arrays.fill(bytes, (byte) 0);
            throw ProviderAttachmentException.validation("SLACK_FILE_SIZE_MISMATCH");
        }
        return bytes;
    }

    private static String providerFileId(JsonNode file) {
        if (file == null || !file.isObject()) {
            throw SlackInboundProcessingException.malformed(
                    "MALFORMED_SLACK_FILE",
                    "Slack attachment entry must be an object.");
        }
        JsonNode id = file.get("id");
        if (id == null || !id.isTextual() || id.stringValue().isBlank()) {
            throw SlackInboundProcessingException.malformed(
                    "MALFORMED_SLACK_FILE_ID",
                    "Slack attachment id is required.");
        }
        String value = id.stringValue().strip();
        if (value.length() > 255 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw SlackInboundProcessingException.malformed(
                    "MALFORMED_SLACK_FILE_ID",
                    "Slack attachment id is invalid.");
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static SlackInboundProcessingException inboundFailure(ProviderAttachmentException failure) {
        if (failure.retryable()) {
            return SlackInboundProcessingException.transientFailure(
                    failure.errorCode(),
                    "Slack attachment transfer failed transiently.");
        }
        return SlackInboundProcessingException.permanentFailure(
                failure.errorCode(),
                "Slack attachment transfer failed permanently.");
    }
}
