package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.messaging.MessageDeliveryStatus;
import com.unifiedsupportinbox.messaging.MessageKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "messages")
class MessageEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "external_message_id", length = 255)
    private String externalMessageId;

    @Column(name = "external_thread_key", length = 255, updatable = false)
    private String externalThreadKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private MessageKind kind;

    @Column(name = "author_user_id", updatable = false)
    private UUID authorUserId;

    @Column(name = "author_external_id", length = 255, updatable = false)
    private String authorExternalId;

    @Column(name = "author_name", length = 200)
    private String authorName;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "body_format", nullable = false, length = 16)
    private MessageBodyFormat bodyFormat;

    @Column(name = "inbound", nullable = false, updatable = false)
    private boolean inbound;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 16)
    private MessageDeliveryStatus deliveryStatus;

    @Column(name = "provider_created_at")
    private Instant providerCreatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "correlation_id", nullable = false, length = 128, updatable = false)
    private String correlationId;

    protected MessageEntity() {
    }

    MessageEntity(
            UUID caseId,
            String externalMessageId,
            String externalThreadKey,
            MessageKind kind,
            UUID authorUserId,
            String authorExternalId,
            String authorName,
            String body,
            MessageBodyFormat bodyFormat,
            boolean inbound,
            MessageDeliveryStatus deliveryStatus,
            Instant providerCreatedAt,
            Instant editedAt,
            Instant deletedAt,
            String correlationId) {
        this.caseId = Objects.requireNonNull(caseId, "caseId");
        this.externalMessageId = optionalText(externalMessageId, "externalMessageId", 255);
        this.externalThreadKey = optionalText(externalThreadKey, "externalThreadKey", 255);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.authorUserId = authorUserId;
        this.authorExternalId = optionalText(authorExternalId, "authorExternalId", 255);
        this.authorName = optionalText(authorName, "authorName", 200);
        this.body = Objects.requireNonNull(body, "body");
        this.bodyFormat = Objects.requireNonNull(bodyFormat, "bodyFormat");
        this.inbound = inbound;
        this.deliveryStatus = deliveryStatus;
        this.providerCreatedAt = providerCreatedAt;
        this.editedAt = editedAt;
        this.deletedAt = deletedAt;
        this.correlationId = requiredText(correlationId, "correlationId", 128);
        validateAuthorship();
    }

    UUID id() { return id; }
    UUID caseId() { return caseId; }
    String externalMessageId() { return externalMessageId; }
    String externalThreadKey() { return externalThreadKey; }
    MessageKind kind() { return kind; }
    UUID authorUserId() { return authorUserId; }
    String authorExternalId() { return authorExternalId; }
    String authorName() { return authorName; }
    String body() { return body; }
    MessageBodyFormat bodyFormat() { return bodyFormat; }
    boolean inbound() { return inbound; }
    MessageDeliveryStatus deliveryStatus() { return deliveryStatus; }
    Instant providerCreatedAt() { return providerCreatedAt; }
    Instant createdAt() { return createdAt; }
    Instant editedAt() { return editedAt; }
    Instant deletedAt() { return deletedAt; }
    String correlationId() { return correlationId; }

    @PrePersist
    void beforeInsert() {
        normalizeAndValidate();
        if (createdAt == null) createdAt = Instant.now();
    }

    @PreUpdate
    void beforeUpdate() {
        normalizeAndValidate();
    }

    private void normalizeAndValidate() {
        externalMessageId = optionalText(externalMessageId, "externalMessageId", 255);
        externalThreadKey = optionalText(externalThreadKey, "externalThreadKey", 255);
        authorExternalId = optionalText(authorExternalId, "authorExternalId", 255);
        authorName = optionalText(authorName, "authorName", 200);
        correlationId = requiredText(correlationId, "correlationId", 128);
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(bodyFormat, "bodyFormat");
        validateAuthorship();
    }

    private void validateAuthorship() {
        boolean valid = switch (kind) {
            case CUSTOMER -> inbound
                    && externalMessageId != null
                    && providerCreatedAt != null
                    && authorUserId == null
                    && authorExternalId != null
                    && deliveryStatus == null;
            case SUPPORT -> !inbound
                    && authorUserId != null
                    && authorExternalId == null
                    && deliveryStatus != null;
            case SYSTEM -> !inbound
                    && authorUserId == null
                    && authorExternalId == null
                    && deliveryStatus == null;
        };
        if (!valid) {
            throw new IllegalArgumentException("Message authorship/direction/delivery combination is invalid.");
        }
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength + ".");
        }
        return normalized;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength + ".");
        }
        return normalized;
    }
}
