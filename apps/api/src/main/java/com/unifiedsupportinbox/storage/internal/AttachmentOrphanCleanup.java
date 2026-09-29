package com.unifiedsupportinbox.storage.internal;

import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = {
        "usi.object-storage.access-key",
        "usi.object-storage.secret-key"
})
class AttachmentOrphanCleanup {

    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final AttachmentObjectStorage objects;

    AttachmentOrphanCleanup(JdbcTemplate jdbc, AttachmentObjectStorage objects) {
        this.jdbc = jdbc;
        this.objects = objects;
    }

    @Scheduled(fixedDelayString = "${usi.attachments.orphan-cleanup-ms:3600000}")
    @Transactional
    public void removeExpiredUploads() {
        List<Orphan> orphans = jdbc.query("""
                SELECT id, storage_key
                FROM attachments
                WHERE message_id IS NULL
                  AND created_at < CURRENT_TIMESTAMP - INTERVAL '24 hours'
                ORDER BY created_at ASC, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """, (rs, rowNum) -> new Orphan(
                        rs.getObject("id", UUID.class),
                        rs.getString("storage_key")), BATCH_SIZE);

        for (Orphan orphan : orphans) {
            try {
                objects.delete(orphan.storageKey());
            } catch (RuntimeException storageFailure) {
                continue;
            }
            jdbc.update("DELETE FROM attachments WHERE id = ? AND message_id IS NULL", orphan.id());
        }
    }

    private record Orphan(UUID id, String storageKey) {
    }
}
