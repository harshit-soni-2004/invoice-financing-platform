package com.ifp.auth.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    private UUID id;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    protected UserAccount() {
    }

    private UserAccount(UUID id, String email, String passwordHash, String displayName, Role role,
                        UserStatus status, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.email = normalizeEmail(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.displayName = Objects.requireNonNull(displayName).strip();
        this.role = Objects.requireNonNull(role);
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Public sign-up. Financiers move money, so they wait for an administrator before they can sign in. */
    public static UserAccount selfRegistered(UUID id, String email, String passwordHash, String displayName,
                                             Role role, Instant now) {
        if (!role.isSelfRegistrable()) {
            throw new IllegalArgumentException("Role cannot be self-registered: " + role);
        }
        UserStatus initial = role == Role.FINANCIER ? UserStatus.PENDING_APPROVAL : UserStatus.ACTIVE;
        return new UserAccount(id, email, passwordHash, displayName, role, initial, now);
    }

    /** Operator-provisioned administrator; never reachable from the public API. */
    public static UserAccount administrator(UUID id, String email, String passwordHash, String displayName, Instant now) {
        return new UserAccount(id, email, passwordHash, displayName, Role.ADMIN, UserStatus.ACTIVE, now);
    }

    public static String normalizeEmail(String email) {
        return Objects.requireNonNull(email).strip().toLowerCase(Locale.ROOT);
    }

    /** @return false if the account was not waiting for approval (nothing changed). */
    public boolean activate(Instant now) {
        if (status != UserStatus.PENDING_APPROVAL) {
            return false;
        }
        status = UserStatus.ACTIVE;
        updatedAt = now;
        return true;
    }

    /** @return false if the account was not waiting for approval (nothing changed). A blank reason is stored as none. */
    public boolean reject(String reason, Instant now) {
        if (status != UserStatus.PENDING_APPROVAL) {
            return false;
        }
        status = UserStatus.REJECTED;
        rejectionReason = reason == null || reason.isBlank() ? null : reason.strip();
        rejectedAt = now;
        updatedAt = now;
        return true;
    }

    public boolean canSignIn() {
        return status == UserStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }
}
