package com.unifiedsupportinbox.cases.internal;

import com.unifiedsupportinbox.cases.CaseResolutionCategory;
import com.unifiedsupportinbox.cases.CaseStatus;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "cases")
class CaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Generated
    @ColumnDefault("('CASE-' || lpad(nextval('case_reference_seq')::text, 8, '0'))")
    @Column(name = "reference", nullable = false, length = 32, updatable = false)
    private String reference;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "integration_id", nullable = false, updatable = false)
    private UUID integrationId;

    @Column(name = "channel_id", nullable = false, updatable = false)
    private UUID channelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16, updatable = false)
    private IntegrationProvider provider;

    @Column(name = "external_conversation_id", nullable = false, length = 255, updatable = false)
    private String externalConversationId;

    @Column(name = "external_thread_key", length = 255, updatable = false)
    private String externalThreadKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CaseStatus status = CaseStatus.NEW;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "waiting_until")
    private Instant waitingUntil;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "ignored_at")
    private Instant ignoredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_category", length = 32)
    private CaseResolutionCategory resolutionCategory;

    @Column(name = "related_case_id", updatable = false)
    private UUID relatedCaseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CaseEntity() {
    }

    CaseEntity(
            UUID customerId,
            UUID integrationId,
            UUID channelId,
            IntegrationProvider provider,
            String externalConversationId,
            String externalThreadKey,
            UUID relatedCaseId) {
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.integrationId = Objects.requireNonNull(integrationId, "integrationId");
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.externalConversationId = requiredText(externalConversationId, "externalConversationId", 255);
        this.externalThreadKey = optionalText(externalThreadKey, "externalThreadKey", 255);
        this.relatedCaseId = relatedCaseId;
        this.status = CaseStatus.NEW;
    }

    UUID id() { return id; }
    String reference() { return reference; }
    UUID customerId() { return customerId; }
    UUID integrationId() { return integrationId; }
    UUID channelId() { return channelId; }
    IntegrationProvider provider() { return provider; }
    String externalConversationId() { return externalConversationId; }
    String externalThreadKey() { return externalThreadKey; }
    CaseStatus status() { return status; }
    UUID ownerUserId() { return ownerUserId; }
    Instant claimedAt() { return claimedAt; }
    Instant waitingUntil() { return waitingUntil; }
    Instant resolvedAt() { return resolvedAt; }
    Instant ignoredAt() { return ignoredAt; }
    CaseResolutionCategory resolutionCategory() { return resolutionCategory; }
    UUID relatedCaseId() { return relatedCaseId; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
    Instant lastActivityAt() { return lastActivityAt; }
    long version() { return version; }

    @PrePersist
    void beforeInsert() {
        externalConversationId = requiredText(externalConversationId, "externalConversationId", 255);
        externalThreadKey = optionalText(externalThreadKey, "externalThreadKey", 255);
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (lastActivityAt == null) lastActivityAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Instant.now();
    }

    private static String requiredText(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters.");
        }
        return normalized;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain at most " + maxLength + " characters.");
        }
        return normalized;
    }
}
