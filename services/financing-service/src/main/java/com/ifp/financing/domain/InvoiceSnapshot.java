package com.ifp.financing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * State of an invoice as reported by invoice-service. {@code sourceVersion} is invoice-service's optimistic-lock
 * version, which increases with every change, so it orders updates even when they arrive duplicated or out of order.
 */
public record InvoiceSnapshot(
        UUID invoiceId,
        UUID sellerId,
        String invoiceNumber,
        String buyerName,
        BigDecimal amount,
        String currency,
        LocalDate issueDate,
        LocalDate dueDate,
        InvoiceState status,
        long sourceVersion,
        Instant sourceUpdatedAt) {

    public InvoiceSnapshot {
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(invoiceNumber, "invoiceNumber");
        Objects.requireNonNull(buyerName, "buyerName");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(issueDate, "issueDate");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sourceUpdatedAt, "sourceUpdatedAt");
        if (!Money.CURRENCY.equals(currency)) {
            throw new IllegalArgumentException("Only INR invoices are supported");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (!dueDate.isAfter(issueDate)) {
            throw new IllegalArgumentException("dueDate must be after issueDate");
        }
        if (sourceVersion < 0) {
            throw new IllegalArgumentException("sourceVersion must not be negative");
        }
        amount = Money.exact(amount, "amount");
    }
}
