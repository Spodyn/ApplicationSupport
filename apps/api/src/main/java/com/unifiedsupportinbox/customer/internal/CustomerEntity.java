package com.unifiedsupportinbox.customer.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "customers")
class CustomerEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "external_ref", length = 160)
    private String externalRef;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerEntity() {
    }

    CustomerEntity(String name, String externalRef) {
        this.name = normalizeName(name);
        this.externalRef = normalizeExternalRef(externalRef);
        this.active = true;
    }

    UUID id() { return id; }
    String name() { return name; }
    String externalRef() { return externalRef; }
    boolean active() { return active; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }

    void update(String name, String externalRef) {
        this.name = normalizeName(name);
        this.externalRef = normalizeExternalRef(externalRef);
    }

    void deactivate() {
        active = false;
    }

    @PrePersist
    void beforeInsert() {
        normalize();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        normalize();
        updatedAt = Instant.now();
    }

    private void normalize() {
        name = normalizeName(name);
        externalRef = normalizeExternalRef(externalRef);
    }

    private static String normalizeName(String value) {
        String normalized = Objects.requireNonNull(value, "name").strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException("Customer name must contain 1 to 160 characters.");
        }
        return normalized;
    }

    private static String normalizeExternalRef(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 160) {
            throw new IllegalArgumentException("Customer external reference must contain at most 160 characters.");
        }
        return normalized;
    }
}
