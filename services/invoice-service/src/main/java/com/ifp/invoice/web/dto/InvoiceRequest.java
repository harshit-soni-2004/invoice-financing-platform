package com.ifp.invoice.web.dto;

import com.ifp.invoice.domain.InvoiceDetails;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body for creating or replacing a DRAFT invoice. There is deliberately no seller or currency field:
 * the seller comes from the access token and the currency is always INR.
 */
public record InvoiceRequest(
        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9/-]*$", message = "may contain only letters, digits, '/' and '-'")
        String invoiceNumber,

        @NotBlank
        @Size(max = 200)
        String buyerName,

        @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", message = "must be a valid 15-character GSTIN")
        String buyerGstin,

        @Size(max = 500)
        String description,

        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount,

        @NotNull
        LocalDate issueDate,

        @NotNull
        LocalDate dueDate) {

    public InvoiceDetails toDetails() {
        return new InvoiceDetails(invoiceNumber, buyerName, buyerGstin, description, amount, issueDate, dueDate);
    }
}
