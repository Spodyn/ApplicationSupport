package com.unifiedsupportinbox.readstate.internal;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "case_read_states")
class CaseReadStateEntity {

    @EmbeddedId
    private CaseReadStateId id;

    @Column(name = "last_read_message_id")
    private UUID lastReadMessageId;

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CaseReadStateEntity() {
    }

    CaseReadStateEntity(UUID userId, UUID caseId, UUID lastReadMessageId, Instant lastReadAt) {
        this.id = new CaseReadStateId(userId, caseId);
        this.lastReadMessageId = lastReadMessageId;
        this.lastReadAt = lastReadAt;
        validatePositionPair();
    }

    UUID userId() { return id.userId(); }
    UUID caseId() { return id.caseId(); }
    UUID lastReadMessageId() { return lastReadMessageId; }
    Instant lastReadAt() { return lastReadAt; }
    Instant updatedAt() { return updatedAt; }

    @PrePersist
    void beforeInsert() {
        Objects.requireNonNull(id, "id");
        validatePositionPair();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void beforeUpdate() {
        validatePositionPair();
        updatedAt = Instant.now();
    }

    private void validatePositionPair() {
        if ((lastReadMessageId == null) != (lastReadAt == null)) {
            throw new IllegalArgumentException("lastReadMessageId and lastReadAt must be both null or both set.");
        }
    }
}
