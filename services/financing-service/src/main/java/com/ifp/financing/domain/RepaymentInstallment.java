package com.ifp.financing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "repayment_installments")
public class RepaymentInstallment {

    public enum Status { PENDING, PARTIALLY_PAID, PAID, OVERDUE }

    @Id
    private UUID id;

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "principal_due", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal principalDue;

    @Column(name = "fee_due", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal feeDue;

    @Column(name = "principal_paid", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalPaid;

    @Column(name = "fee_paid", nullable = false, precision = 19, scale = 2)
    private BigDecimal feePaid;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected RepaymentInstallment() {
    }

    public RepaymentInstallment(UUID id, UUID agreementId, int sequenceNo, LocalDate dueDate,
                                BigDecimal principalDue, BigDecimal feeDue) {
        this.id = id;
        this.agreementId = agreementId;
        this.sequenceNo = sequenceNo;
        this.dueDate = dueDate;
        this.principalDue = principalDue;
        this.feeDue = feeDue;
        this.principalPaid = Money.ZERO;
        this.feePaid = Money.ZERO;
    }

    void pay(BigDecimal principal, BigDecimal fee) {
        BigDecimal newPrincipal = principalPaid.add(principal);
        BigDecimal newFee = feePaid.add(fee);
        if (newPrincipal.compareTo(principalDue) > 0 || newFee.compareTo(feeDue) > 0) {
            throw new IllegalStateException("Installment overpaid");
        }
        principalPaid = newPrincipal;
        feePaid = newFee;
    }

    public BigDecimal principalRemaining() {
        return principalDue.subtract(principalPaid);
    }

    public BigDecimal feeRemaining() {
        return feeDue.subtract(feePaid);
    }

    public BigDecimal remaining() {
        return principalRemaining().add(feeRemaining());
    }

    public BigDecimal totalDue() {
        return principalDue.add(feeDue);
    }

    /** Overdue is derived: past its due date (in the business zone) with something still unpaid. */
    public Status statusOn(LocalDate today) {
        if (remaining().signum() == 0) {
            return Status.PAID;
        }
        if (dueDate.isBefore(today)) {
            return Status.OVERDUE;
        }
        return principalPaid.signum() > 0 || feePaid.signum() > 0 ? Status.PARTIALLY_PAID : Status.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getPrincipalDue() {
        return principalDue;
    }

    public BigDecimal getFeeDue() {
        return feeDue;
    }

    public BigDecimal getPrincipalPaid() {
        return principalPaid;
    }

    public BigDecimal getFeePaid() {
        return feePaid;
    }
}
