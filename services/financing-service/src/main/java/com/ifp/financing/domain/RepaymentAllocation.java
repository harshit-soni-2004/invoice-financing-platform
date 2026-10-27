package com.ifp.financing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "repayment_allocations")
public class RepaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repayment_id", nullable = false, updatable = false)
    private UUID repaymentId;

    @Column(name = "installment_id", nullable = false, updatable = false)
    private UUID installmentId;

    @Column(name = "principal_part", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal principalPart;

    @Column(name = "fee_part", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal feePart;

    protected RepaymentAllocation() {
    }

    public RepaymentAllocation(UUID repaymentId, UUID installmentId, BigDecimal principalPart, BigDecimal feePart) {
        this.repaymentId = repaymentId;
        this.installmentId = installmentId;
        this.principalPart = principalPart;
        this.feePart = feePart;
    }

    public UUID getInstallmentId() {
        return installmentId;
    }

    public BigDecimal getPrincipalPart() {
        return principalPart;
    }

    public BigDecimal getFeePart() {
        return feePart;
    }
}
