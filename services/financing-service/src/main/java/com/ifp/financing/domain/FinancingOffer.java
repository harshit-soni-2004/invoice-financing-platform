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
import java.util.UUID;

@Entity
@Table(name = "financing_offers")
public class FinancingOffer {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private UUID sellerId;

    @Column(name = "financier_id", nullable = false, updatable = false)
    private UUID financierId;

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

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OfferStatus status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FinancingOffer() {
    }

    public static FinancingOffer create(UUID id, FinanceableInvoice invoice, UUID financierId, OfferTerms terms,
                                        Instant expiresAt, Instant now) {
        FinancingOffer offer = new FinancingOffer();
        offer.id = id;
        offer.invoiceId = invoice.getInvoiceId();
        offer.sellerId = invoice.getSellerId();
        offer.financierId = financierId;
        offer.advanceAmount = terms.advanceAmount();
        offer.annualRate = terms.annualRate();
        offer.processingFee = terms.processingFee();
        offer.installmentCount = terms.installmentCount();
        offer.currency = Money.CURRENCY;
        offer.expiresAt = expiresAt;
        offer.status = OfferStatus.OFFERED;
        offer.createdAt = now;
        offer.updatedAt = now;
        return offer;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void accept(Instant now) {
        moveTo(OfferStatus.ACCEPTED, null, now);
    }

    public void reject(String reason, Instant now) {
        moveTo(OfferStatus.REJECTED, blankToNull(reason), now);
    }

    public void withdraw(String reason, Instant now) {
        moveTo(OfferStatus.WITHDRAWN, blankToNull(reason), now);
    }

    public void expire(Instant now) {
        moveTo(OfferStatus.EXPIRED, "Offer validity ended", now);
    }

    public void close(String reason, Instant now) {
        moveTo(OfferStatus.CLOSED, reason, now);
    }

    public OfferTerms terms() {
        return new OfferTerms(advanceAmount, annualRate, processingFee, installmentCount);
    }

    public boolean isMadeBy(UUID userId) {
        return financierId.equals(userId);
    }

    public boolean isForSeller(UUID userId) {
        return sellerId.equals(userId);
    }

    private void moveTo(OfferStatus target, String reason, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException("offer", status, target);
        }
        status = target;
        statusReason = reason;
        decidedAt = now;
        updatedAt = now;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public UUID getId() {
        return id;
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

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
