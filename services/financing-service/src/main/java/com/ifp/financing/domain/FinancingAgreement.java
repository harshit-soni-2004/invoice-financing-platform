package com.ifp.financing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The financing contract created when a seller accepts an offer. Terms are copied from the offer so later changes
 * elsewhere can never alter them. Repaid totals are kept on the agreement (and versioned) so concurrent repayments
 * serialize on this row.
 */
@Entity
@Table(name = "financing_agreements")
public class FinancingAgreement {

    @Id
    private UUID id;

    @Column(name = "offer_id", nullable = false, updatable = false)
    private UUID offerId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private UUID sellerId;

    @Column(name = "financier_id", nullable = false, updatable = false)
    private UUID financierId;

    @Column(name = "invoice_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal invoiceAmount;

    @Column(name = "advance_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal advanceAmount;

    @Column(name = "annual_rate", nullable = false, precision = 7, scale = 4, updatable = false)
    private BigDecimal annualRate;

    @Column(name = "processing_fee", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal processingFee;

    @Column(name = "installment_count", nullable = false, updatable = false)
    private int installmentCount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "maturity_date", nullable = false, updatable = false)
    private LocalDate maturityDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AgreementStatus status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "fee_amount", precision = 19, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "total_repayable", precision = 19, scale = 2)
    private BigDecimal totalRepayable;

    @Column(name = "principal_repaid", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalRepaid;

    @Column(name = "fee_repaid", nullable = false, precision = 19, scale = 2)
    private BigDecimal feeRepaid;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "disbursed_at")
    private Instant disbursedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FinancingAgreement() {
    }

    public static FinancingAgreement fromAcceptedOffer(UUID id, FinancingOffer offer, FinanceableInvoice invoice, Instant now) {
        if (offer.getStatus() != OfferStatus.ACCEPTED) {
            throw new IllegalStateException("Agreements are created only from accepted offers");
        }
        FinancingAgreement agreement = new FinancingAgreement();
        agreement.id = id;
        agreement.offerId = offer.getId();
        agreement.invoiceId = offer.getInvoiceId();
        agreement.sellerId = offer.getSellerId();
        agreement.financierId = offer.getFinancierId();
        agreement.invoiceAmount = invoice.getAmount();
        agreement.advanceAmount = offer.getAdvanceAmount();
        agreement.annualRate = offer.getAnnualRate();
        agreement.processingFee = offer.getProcessingFee();
        agreement.installmentCount = offer.getInstallmentCount();
        agreement.currency = Money.CURRENCY;
        agreement.maturityDate = invoice.getDueDate();
        agreement.status = AgreementStatus.PENDING_APPROVAL;
        agreement.principalRepaid = Money.ZERO;
        agreement.feeRepaid = Money.ZERO;
        agreement.createdAt = now;
        agreement.updatedAt = now;
        return agreement;
    }

    public void approve(Instant now) {
        moveTo(AgreementStatus.APPROVED, null, now);
        approvedAt = now;
    }

    public void reject(String reason, Instant now) {
        moveTo(AgreementStatus.REJECTED, blankToNull(reason), now);
    }

    public void cancel(String reason, Instant now) {
        moveTo(AgreementStatus.CANCELLED, blankToNull(reason), now);
    }

    /** Fixes the fee (it depends on days to maturity) and the total repayable at the moment of disbursement. */
    public void markDisbursed(BigDecimal fee, Instant now) {
        ensureCanTransitionTo(AgreementStatus.DISBURSED);
        feeAmount = fee;
        totalRepayable = advanceAmount.add(fee);
        moveTo(AgreementStatus.DISBURSED, null, now);
        disbursedAt = now;
    }

    /** Applies an already-allocated repayment; settles the agreement when nothing is outstanding. */
    public void applyRepayment(BigDecimal principalPart, BigDecimal feePart, Instant now) {
        if (!status.acceptsRepayments()) {
            throw new ConflictException("REPAYMENT_NOT_ALLOWED", "Repayments are not accepted while the agreement is " + status);
        }
        BigDecimal newPrincipal = principalRepaid.add(principalPart);
        BigDecimal newFee = feeRepaid.add(feePart);
        if (newPrincipal.compareTo(advanceAmount) > 0 || newFee.compareTo(feeAmount) > 0) {
            throw new IllegalStateException("Allocation exceeds the amounts due");
        }
        principalRepaid = newPrincipal;
        feeRepaid = newFee;
        updatedAt = now;
        if (outstanding().signum() == 0) {
            moveTo(AgreementStatus.SETTLED, null, now);
            settledAt = now;
        } else if (status == AgreementStatus.DISBURSED) {
            moveTo(AgreementStatus.REPAYMENT_IN_PROGRESS, null, now);
        }
    }

    public void ensureCanTransitionTo(AgreementStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException("agreement", status, target);
        }
    }

    /** Total still owed; equals the advance plus fee before any repayment, and zero once settled. */
    public BigDecimal outstanding() {
        if (totalRepayable == null) {
            return Money.ZERO;
        }
        return totalRepayable.subtract(principalRepaid).subtract(feeRepaid);
    }

    public BigDecimal principalOutstanding() {
        return totalRepayable == null ? Money.ZERO : advanceAmount.subtract(principalRepaid);
    }

    public BigDecimal feeOutstanding() {
        return feeAmount == null ? Money.ZERO : feeAmount.subtract(feeRepaid);
    }

    public boolean isParty(UUID userId) {
        return sellerId.equals(userId) || financierId.equals(userId);
    }

    private void moveTo(AgreementStatus target, String reason, Instant now) {
        ensureCanTransitionTo(target);
        status = target;
        statusReason = reason;
        updatedAt = now;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public UUID getFinancierId() {
        return financierId;
    }

    public BigDecimal getInvoiceAmount() {
        return invoiceAmount;
    }

    public BigDecimal getAdvanceAmount() {
        return advanceAmount;
    }

    public BigDecimal getAnnualRate() {
        return annualRate;
    }

    public BigDecimal getProcessingFee() {
        return processingFee;
    }

    public int getInstallmentCount() {
        return installmentCount;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getMaturityDate() {
        return maturityDate;
    }

    public AgreementStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public BigDecimal getTotalRepayable() {
        return totalRepayable;
    }

    public BigDecimal getPrincipalRepaid() {
        return principalRepaid;
    }

    public BigDecimal getFeeRepaid() {
        return feeRepaid;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getDisbursedAt() {
        return disbursedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
