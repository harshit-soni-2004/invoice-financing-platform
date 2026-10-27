package com.ifp.financing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Record of a simulated payout. No bank or payment system is ever contacted. */
@Entity
@Table(name = "disbursements")
public class Disbursement {

    public static final String STATUS_SIMULATED_COMPLETED = "SIMULATED_COMPLETED";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    private UUID id;

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private String status;

    @Column(name = "reference", nullable = false, length = 40, updatable = false)
    private String reference;

    @Column(name = "initiated_by", nullable = false, updatable = false)
    private UUID initiatedBy;

    @Column(name = "disbursed_at", nullable = false, updatable = false)
    private Instant disbursedAt;

    protected Disbursement() {
    }

    public static Disbursement simulated(UUID id, FinancingAgreement agreement, UUID initiatedBy, Instant now) {
        Disbursement disbursement = new Disbursement();
        disbursement.id = id;
        disbursement.agreementId = agreement.getId();
        disbursement.amount = agreement.getAdvanceAmount();
        disbursement.currency = Money.CURRENCY;
        disbursement.status = STATUS_SIMULATED_COMPLETED;
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        disbursement.reference = "SIM-" + HexFormat.of().withUpperCase().formatHex(random);
        disbursement.initiatedBy = initiatedBy;
        disbursement.disbursedAt = now;
        return disbursement;
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

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getReference() {
        return reference;
    }

    public UUID getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getDisbursedAt() {
        return disbursedAt;
    }
}
