package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.channel.ChannelIngestionPolicy;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Command;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Result;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.provider.ProviderAttachmentGateway;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import com.unifiedsupportinbox.storage.AttachmentSecurityService;
import com.unifiedsupportinbox.storage.AttachmentStorageKey;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
class SlackInboundAttachmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlackInboundAttachmentService.class);

    private final SlackProviderAttachmentGateway gateway;
    private final ObjectProvider<AttachmentObjectStorage> objectStorage;
    private final ObjectProvider<AttachmentSecurityService> security;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OutboxEventStore outbox;
    private final ChannelIngestionPolicy channels;
    private final ObjectMapper json;

    SlackInboundAttachmentService(SlackProviderAttachmentGateway gateway,
            ObjectProvider<AttachmentObjectStorage> objectStorage, ObjectProvider<AttachmentSecurityService> security,
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, OutboxEventStore outbox, ObjectMapper json,
            ChannelIngestionPolicy channels) {
        this.gateway = gateway;
        this.objectStorage = objectStorage;
        this.security = security;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
        this.json = json;
        this.channels = channels;
    }

    // Runs in the same transaction as message persistence. Only references are recorded here.
    void enqueue(Command command, Result persisted, List<SlackFileReference> files) {
        boolean added = false;
        for (SlackFileReference file : files) {
            added |= jdbc.update("""
                    INSERT INTO attachments (id, message_id, case_id, original_filename, size_bytes,
                        provider_file_id, slack_ingest_pending)
                    SELECT ?, ?, ?, ?, 0, ?, TRUE
                    WHERE EXISTS (SELECT 1 FROM messages WHERE id = ? AND deleted_at IS NULL)
                      AND NOT EXISTS (SELECT 1 FROM attachments WHERE message_id = ? AND provider_file_id = ?)
                      AND (SELECT count(*) FROM attachments WHERE message_id = ?) < 10
                    """, UUID.randomUUID(), persisted.messageId(), persisted.caseId(), file.filename(), file.id(),
                    persisted.messageId(), persisted.messageId(), file.id(), persisted.messageId()) == 1;
        }
        if (added && !persisted.created()) {
            publishUpdate(persisted.messageId(), persisted.caseId(), command.correlationId());
        }
    }

    void processNext() {
        UUID lease = UUID.randomUUID();
        Job job = jdbc.query("""
                WITH next AS (
                    SELECT a.id FROM attachments a WHERE slack_ingest_pending
                      AND (slack_ingest_until IS NULL OR slack_ingest_until <= CURRENT_TIMESTAMP)
                    ORDER BY a.created_at, a.id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE attachments a SET slack_ingest_token = ?, slack_ingest_until = CURRENT_TIMESTAMP + interval '5 minutes',
                    scan_status = CASE WHEN scan_status = 'SCANNING' THEN 'PENDING' ELSE scan_status END,
                    scan_error = CASE WHEN scan_status = 'SCANNING' THEN NULL ELSE scan_error END
                FROM next, messages m, cases c
                WHERE a.id = next.id AND a.message_id = m.id AND c.id = m.case_id
                RETURNING a.id, a.message_id, a.case_id, a.provider_file_id, a.storage_key, c.integration_id, c.external_conversation_id, m.correlation_id
                """, (rs, row) -> new Job(rs.getObject("id", UUID.class), rs.getObject("message_id", UUID.class),
                rs.getObject("case_id", UUID.class), rs.getString("provider_file_id"), rs.getString("storage_key"),
                rs.getObject("integration_id", UUID.class), rs.getString("external_conversation_id"),
                rs.getString("correlation_id")), lease)
                .stream().findFirst().orElse(null);
        if (job == null) return;

        if (channels.resolve(job.integrationId(), job.channel())
                .filter(channel -> channel.active() && !channel.ignoredForInbound()).isEmpty()) {
            finish(job, lease, "SLACK_DOWNLOAD_CHANNEL_UNAVAILABLE");
            return;
        }

        AttachmentObjectStorage objects = objectStorage.getIfUnique();
        AttachmentSecurityService scanner = security.getIfUnique();
        if (objects == null || scanner == null) {
            finish(job, lease, "SLACK_DOWNLOAD_STORAGE_UNAVAILABLE");
            return;
        }

        try {
            if (job.storageKey() == null && !download(job, lease, objects)) return;
        } catch (ProviderAttachmentException failure) {
            finish(job, lease, "SLACK_DOWNLOAD_" + failure.errorCode());
            return;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Slack attachment transfer failed: attachmentId={}, type={}", job.id(), failure.getClass().getSimpleName());
            finish(job, lease, "SLACK_DOWNLOAD_FAILED");
            return;
        }

        try {
            if (scanner.scan(job.id()).scanStatus() != AttachmentScanStatus.SCANNING) finish(job, lease, null);
        } catch (RuntimeException failure) {
            LOGGER.warn("Slack attachment scan failed: attachmentId={}, type={}", job.id(), failure.getClass().getSimpleName());
            finish(job, lease, "SLACK_SCAN_FAILED");
        }
    }

    private boolean download(Job job, UUID lease, AttachmentObjectStorage objects) throws IOException {
        try (var downloaded = gateway.download(new ProviderAttachmentGateway.DownloadRequest(
                job.integrationId(), job.fileId(), ProviderAttachmentGateway.MAX_FILE_BYTES))) {
            var details = downloaded.metadata();
            String key = AttachmentStorageKey.generate();
            MessageDigest digest = sha256();
            boolean associated = false;
            try (var content = new DigestInputStream(downloaded.content(), digest)) {
                objects.put(key, content, details.sizeBytes(), details.contentType());
                String hash = HexFormat.of().formatHex(digest.digest());
                associated = Boolean.TRUE.equals(transactions.execute(status -> {
                    jdbc.query("SELECT id FROM messages WHERE id = ? FOR UPDATE", rs -> { }, job.messageId());
                    return jdbc.update("""
                            UPDATE attachments SET storage_key = ?, sha256 = ?, original_filename = ?, content_type = ?, size_bytes = ?
                            WHERE id = ? AND slack_ingest_token = ? AND storage_key IS NULL
                              AND ? + (SELECT COALESCE(sum(size_bytes), 0) FROM attachments WHERE message_id = ?) <= ?
                            """, key, hash, details.filename(), details.contentType(), details.sizeBytes(), job.id(),
                            lease, details.sizeBytes(), job.messageId(), 50L * 1024 * 1024) == 1;
                }));
                if (!associated) finish(job, lease, "SLACK_DOWNLOAD_MESSAGE_LIMIT");
                return associated;
            } finally {
                if (!associated) objects.delete(key);
            }
        }
    }

    private void finish(Job job, UUID lease, String error) {
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    UPDATE attachments SET slack_ingest_pending = FALSE, slack_ingest_token = NULL, slack_ingest_until = NULL,
                        scan_status = CASE WHEN CAST(? AS text) IS NULL THEN scan_status ELSE 'ERROR' END,
                        scan_error = COALESCE(?, scan_error)
                    WHERE id = ? AND slack_ingest_token = ?
                    """, error, error, job.id(), lease);
            if (updated == 1) publishUpdate(job.messageId(), job.caseId(), job.correlationId());
        });
    }

    private void publishUpdate(UUID messageId, UUID caseId, String correlationId) {
        String payload = json.createObjectNode().put("messageId", messageId.toString()).put("caseId", caseId.toString()).toString();
        outbox.append("message.updated", "message", messageId, payload, correlationId);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Job(UUID id, UUID messageId, UUID caseId, String fileId, String storageKey,
            UUID integrationId, String channel, String correlationId) { }
}
