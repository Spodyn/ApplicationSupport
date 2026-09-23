package com.unifiedsupportinbox.readstate.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
class CaseReadStateId implements Serializable {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    protected CaseReadStateId() {
    }

    CaseReadStateId(UUID userId, UUID caseId) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.caseId = Objects.requireNonNull(caseId, "caseId");
    }

    UUID userId() { return userId; }
    UUID caseId() { return caseId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CaseReadStateId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(caseId, that.caseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, caseId);
    }
}
