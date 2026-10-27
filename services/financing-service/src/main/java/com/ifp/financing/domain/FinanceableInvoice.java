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

/** financing-service's own copy of an invoice. Only {@link #apply} changes it; users never write it. */
@Entity
@Table(name = "financeable_invoices")
public class FinanceableInvoice {

    @Id
    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "invoice_number", nullable = false, length = 50)
    private String invoiceNumber;

    @Column(name = "buyer_name", nullable = false, length = 200)
    private String buyerName;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_status", nullable = false, length = 30)
    private InvoiceState invoiceStatus;

    @Column(name = "source_version", nullable = false)
    private long sourceVersion;

    @Column(name = "source_updated_at", nullable = false)
    private Instant sourceUpdatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FinanceableInvoice() {
    }

    public static FinanceableInvoice from(InvoiceSnapshot snapshot, Instant now) {
        FinanceableInvoice invoice = new FinanceableInvoice();
        invoice.invoiceId = snapshot.invoiceId();
        invoice.createdAt = now;
        invoice.copy(snapshot, now);
        return invoice;
    }

    /**
     * Applies a newer snapshot. Returns false (and changes nothing) for duplicates and out-of-order snapshots.
     * The seller of an invoice never changes; a snapshot claiming otherwise is rejected as corrupt.
     */
    public boolean apply(InvoiceSnapshot snapshot, Instant now) {
        if (!snapshot.invoiceId().equals(invoiceId)) {
            throw new IllegalArgumentException("Snapshot belongs to another invoice");
        }
        if (snapshot.sourceVersion() <= sourceVersion) {
            return false;
        }
        if (!snapshot.sellerId().equals(sellerId)) {
            throw new IllegalArgumentException("Invoice seller cannot change");
        }
        copy(snapshot, now);
        return true;
    }

    public boolean isListedForFinancing() {
        return invoiceStatus == InvoiceState.FINANCING_AVAILABLE;
    }

    /** True when the due date is far enough away to be financed (stale invoices close to or past due are not). */
    public boolean isWithinMaturityWindow(LocalDate today, int minDaysToMaturity) {
        return !dueDate.isBefore(today.plusDays(minDaysToMaturity));
    }

    public boolean isOwnedBy(UUID userId) {
        return sellerId.equals(userId);
    }

    private void copy(InvoiceSnapshot snapshot, Instant now) {
        sellerId = snapshot.sellerId();
        invoiceNumber = snapshot.invoiceNumber();
        buyerName = snapshot.buyerName();
        amount = snapshot.amount();
        currency = snapshot.currency();
        issueDate = snapshot.issueDate();
        dueDate = snapshot.dueDate();
        invoiceStatus = snapshot.status();
        sourceVersion = snapshot.sourceVersion();
        sourceUpdatedAt = snapshot.sourceUpdatedAt();
        updatedAt = now;
    }

    public UUID getInvoiceId() {
        return invoiceId;
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

    public InvoiceState getInvoiceStatus() {
        return invoiceStatus;
    }

    public long getSourceVersion() {
        return sourceVersion;
    }

    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
