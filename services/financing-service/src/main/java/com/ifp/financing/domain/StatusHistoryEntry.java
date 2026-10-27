package com.ifp.financing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Immutable lifecycle step of an offer or agreement, written in the same transaction as the change. */
@Entity
@Table(name = "financing_status_history")
public class StatusHistoryEntry {

    public static final String OFFER = "OFFER";
    public static final String AGREEMENT = "AGREEMENT";
    public static final String SYSTEM_ROLE = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 20, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "from_status", length = 30, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 30, updatable = false)
    private String toStatus;

    /** Null for system actions such as expiry or invoice withdrawal. */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_role", nullable = false, length = 20, updatable = false)
    private String actorRole;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected StatusHistoryEntry() {
    }

    public StatusHistoryEntry(String entityType, UUID entityId, Enum<?> fromStatus, Enum<?> toStatus, UUID actorId,
                              String actorRole, String reason, Instant occurredAt) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.fromStatus = fromStatus == null ? null : fromStatus.name();
        this.toStatus = toStatus.name();
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
