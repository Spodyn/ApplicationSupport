package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.identity.UserRole;
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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "users")
class UserAccount {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private UserRole role;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected UserAccount() {
    }

    UserAccount(
            String email,
            String displayName,
            String passwordHash,
            UserRole role,
            boolean active,
            Instant validFrom,
            Instant validUntil) {
        this.email = normalizeEmail(email);
        this.displayName = normalizeDisplayName(displayName);
        this.passwordHash = normalizePasswordHash(passwordHash);
        this.role = Objects.requireNonNull(role, "role");
        this.active = active;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        validateValidityWindow();
    }

    UUID id() {
        return id;
    }

    String email() {
        return email;
    }

    String displayName() {
        return displayName;
    }

    String passwordHash() {
        return passwordHash;
    }

    UserRole role() {
        return role;
    }

    boolean active() {
        return active;
    }

    Instant validFrom() {
        return validFrom;
    }

    Instant validUntil() {
        return validUntil;
    }

    Instant lastLoginAt() {
        return lastLoginAt;
    }

    long version() {
        return version;
    }

    boolean isSessionEligibleAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return active
                && (validFrom == null || !instant.isBefore(validFrom))
                && (validUntil == null || instant.isBefore(validUntil));
    }

    void recordSuccessfulLogin(Instant instant) {
        lastLoginAt = Objects.requireNonNull(instant, "instant");
    }

    void setActive(boolean active) {
        this.active = active;
    }

    void setValidityWindow(Instant validFrom, Instant validUntil) {
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        validateValidityWindow();
    }

    void setPasswordHash(String passwordHash) {
        this.passwordHash = normalizePasswordHash(passwordHash);
    }

    @PrePersist
    void beforeInsert() {
        normalizeAndValidate();
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        normalizeAndValidate();
        updatedAt = Instant.now();
    }

    private void normalizeAndValidate() {
        email = normalizeEmail(email);
        displayName = normalizeDisplayName(displayName);
        passwordHash = normalizePasswordHash(passwordHash);
        role = Objects.requireNonNull(role, "role");
        validateValidityWindow();
    }

    private void validateValidityWindow() {
        if (validFrom != null && validUntil != null && !validFrom.isBefore(validUntil)) {
            throw new IllegalArgumentException("valid_from must be before valid_until");
        }
    }

    private static String normalizeEmail(String value) {
        String normalized = Objects.requireNonNull(value, "email").strip().toLowerCase(Locale.ROOT);
        if (normalized.length() < 3 || normalized.length() > 320) {
            throw new IllegalArgumentException("email length must be between 3 and 320 characters");
        }
        return normalized;
    }

    private static String normalizeDisplayName(String value) {
        String normalized = Objects.requireNonNull(value, "displayName").strip();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new IllegalArgumentException("displayName length must be between 1 and 200 characters");
        }
        return normalized;
    }

    private static String normalizePasswordHash(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new IllegalArgumentException("passwordHash must be non-blank and at most 255 characters");
        }
        return normalized;
    }
}
