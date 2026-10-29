package com.ifp.financing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A simulated repayment received against an agreement, with its split into principal and fee. */
@Entity
@Table(name = "repayments")
public class Repayment {

    @Id
    private UUID id;

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "principal_component", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal principalComponent;

    @Column(name = "fee_component", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal feeComponent;

    @Column(name = "penalty_component", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal penaltyComponent;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected Repayment() {
    }

    public Repayment(UUID id, UUID agreementId, BigDecimal principalComponent, BigDecimal feeComponent,
                     UUID recordedBy, Instant receivedAt) {
        this(id, agreementId, principalComponent, feeComponent, Money.ZERO, recordedBy, receivedAt);
    }

    public Repayment(UUID id, UUID agreementId, BigDecimal principalComponent, BigDecimal feeComponent,
                     BigDecimal penaltyComponent, UUID recordedBy, Instant receivedAt) {
        this.id = id;
        this.agreementId = agreementId;
        this.principalComponent = principalComponent;
        this.feeComponent = feeComponent;
        this.penaltyComponent = penaltyComponent;
        this.amount = principalComponent.add(feeComponent).add(penaltyComponent);
        this.currency = Money.CURRENCY;
        this.recordedBy = recordedBy;
        this.receivedAt = receivedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPrincipalComponent() {
        return principalComponent;
    }

    public BigDecimal getFeeComponent() {
        return feeComponent;
    }

    public BigDecimal getPenaltyComponent() {
        return penaltyComponent;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getRecordedBy() {
        return recordedBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
