package com.ifp.invoice.web.dto;

import com.ifp.invoice.domain.Invoice;
import com.ifp.invoice.domain.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceSummaryResponse(
        UUID id,
        String invoiceNumber,
        String buyerName,
        BigDecimal amount,
        String currency,
        LocalDate dueDate,
        InvoiceStatus status,
        Instant createdAt) {

    public static InvoiceSummaryResponse from(Invoice invoice) {
        return new InvoiceSummaryResponse(invoice.getId(), invoice.getInvoiceNumber(), invoice.getBuyerName(),
                invoice.getAmount(), invoice.getCurrency(), invoice.getDueDate(), invoice.getStatus(),
                invoice.getCreatedAt());
    }
}
