package com.ifp.invoice.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Seller-editable invoice data. */
public record InvoiceDetails(
        String invoiceNumber,
        String buyerName,
        String buyerGstin,
        String description,
        BigDecimal amount,
        LocalDate issueDate,
        LocalDate dueDate) {
}
