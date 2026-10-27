package com.ifp.invoice.web.dto;

import com.ifp.invoice.domain.Invoice;
import com.ifp.invoice.domain.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID sellerId,
        String invoiceNumber,
        String buyerName,
        String buyerGstin,
        String description,
        BigDecimal amount,
        String currency,
        LocalDate issueDate,
        LocalDate dueDate,
        InvoiceStatus status,
        String statusReason,
        Instant submittedAt,
        Instant buyerConfirmedAt,
        Instant verifiedAt,
        Instant financedAt,
        Instant settledAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(invoice.getId(), invoice.getSellerId(), invoice.getInvoiceNumber(),
                invoice.getBuyerName(), invoice.getBuyerGstin(), invoice.getDescription(), invoice.getAmount(),
                invoice.getCurrency(), invoice.getIssueDate(), invoice.getDueDate(), invoice.getStatus(),
                invoice.getStatusReason(), invoice.getSubmittedAt(), invoice.getBuyerConfirmedAt(),
                invoice.getVerifiedAt(), invoice.getFinancedAt(), invoice.getSettledAt(), invoice.getVersion(),
                invoice.getCreatedAt(), invoice.getUpdatedAt());
    }
}
