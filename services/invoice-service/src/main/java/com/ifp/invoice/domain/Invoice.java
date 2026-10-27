package com.ifp.invoice.domain;

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
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "invoices")
public class Invoice {

    public static final String CURRENCY_INR = "INR";

    @Id
    private UUID id;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private UUID sellerId;

    @Column(name = "invoice_number", nullable = false, length = 50)
    private String invoiceNumber;

    @Column(name = "buyer_name", nullable = false, length = 200)
    private String buyerName;

    @Column(name = "buyer_gstin", length = 15)
    private String buyerGstin;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InvoiceStatus status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "buyer_confirmed_at")
    private Instant buyerConfirmedAt;

    @Column(name = "buyer_confirmed_by")
    private UUID buyerConfirmedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "financed_at")
    private Instant financedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Invoice() {
    }

    public static Invoice createDraft(UUID id, UUID sellerId, InvoiceDetails details, Instant now) {
        Invoice invoice = new Invoice();
        invoice.id = Objects.requireNonNull(id);
        invoice.sellerId = Objects.requireNonNull(sellerId);
        invoice.currency = CURRENCY_INR;
        invoice.status = InvoiceStatus.DRAFT;
        invoice.createdAt = now;
        invoice.applyDetails(details, now);
        return invoice;
    }

    public void updateDetails(InvoiceDetails details, Instant now) {
        if (status != InvoiceStatus.DRAFT) {
            throw new InvoiceNotEditableException(status);
        }
        applyDetails(details, now);
    }

    public void submit(Instant now) {
        moveTo(InvoiceStatus.SUBMITTED, null, now);
        submittedAt = now;
    }

    public void confirmBuyer(UUID adminId, Instant now) {
        moveTo(InvoiceStatus.BUYER_CONFIRMED, null, now);
        buyerConfirmedAt = now;
        buyerConfirmedBy = Objects.requireNonNull(adminId);
    }

    public void verify(UUID adminId, Instant now) {
        moveTo(InvoiceStatus.VERIFIED, null, now);
        verifiedAt = now;
        verifiedBy = Objects.requireNonNull(adminId);
    }

    public void reject(String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("REASON_REQUIRED", "A reason is required to reject an invoice");
        }
        moveTo(InvoiceStatus.REJECTED, reason.strip(), now);
    }

    public void cancel(String reason, Instant now) {
        moveTo(InvoiceStatus.CANCELLED, reason == null || reason.isBlank() ? null : reason.strip(), now);
    }

    public void listForFinancing(Instant now) {
        moveTo(InvoiceStatus.FINANCING_AVAILABLE, null, now);
    }

    /** Triggered only by a FinancingDisbursed event (Phase 5). */
    public void markFinanced(Instant now) {
        moveTo(InvoiceStatus.FINANCED, null, now);
        financedAt = now;
    }

    /** Triggered only by a FinancingSettled event (Phase 5). */
    public void markSettled(Instant now) {
        moveTo(InvoiceStatus.SETTLED, null, now);
        settledAt = now;
    }

    public boolean isOwnedBy(UUID userId) {
        return sellerId.equals(userId);
    }

    /** Lets callers report an invalid transition before evaluating rules that only apply to valid ones. */
    public void ensureCanTransitionTo(InvoiceStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
    }

    private void moveTo(InvoiceStatus target, String reason, Instant now) {
        ensureCanTransitionTo(target);
        status = target;
        statusReason = reason;
        updatedAt = now;
    }

    private void applyDetails(InvoiceDetails details, Instant now) {
        invoiceNumber = details.invoiceNumber().strip();
        buyerName = details.buyerName().strip();
        buyerGstin = details.buyerGstin() == null || details.buyerGstin().isBlank() ? null : details.buyerGstin().strip();
        description = details.description() == null || details.description().isBlank() ? null : details.description().strip();
        amount = details.amount();
        issueDate = details.issueDate();
        dueDate = details.dueDate();
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public String getBuyerGstin() {
        return buyerGstin;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getBuyerConfirmedAt() {
        return buyerConfirmedAt;
    }

    public UUID getBuyerConfirmedBy() {
        return buyerConfirmedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public UUID getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getFinancedAt() {
        return financedAt;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
