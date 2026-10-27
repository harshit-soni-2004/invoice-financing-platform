package com.ifp.invoice.service;

import com.ifp.invoice.domain.Invoice;
import com.ifp.invoice.domain.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Invoice lifecycle events. Every event carries the full invoice snapshot plus invoice-service's row version, so
 * consumers (financing-service's read model in particular) can apply them idempotently and in version order.
 * Free-text fields (description, rejection or cancellation reasons, GSTIN) are deliberately not published.
 */
public final class InvoiceEvents {

    public static final String AGGREGATE = "INVOICE";

    private InvoiceEvents() {
    }

    public record InvoiceChanged(UUID invoiceId, UUID sellerId, String invoiceNumber, String buyerName,
                                 BigDecimal amount, String currency, LocalDate issueDate, LocalDate dueDate,
                                 InvoiceStatus status, InvoiceStatus previousStatus, long sourceVersion,
                                 Instant sourceUpdatedAt) {

        static InvoiceChanged of(Invoice invoice, InvoiceStatus previousStatus) {
            return new InvoiceChanged(invoice.getId(), invoice.getSellerId(), invoice.getInvoiceNumber(),
                    invoice.getBuyerName(), invoice.getAmount(), invoice.getCurrency(), invoice.getIssueDate(),
                    invoice.getDueDate(), invoice.getStatus(), previousStatus, invoice.getVersion(), invoice.getUpdatedAt());
        }
    }

    public static String routingKey(InvoiceStatus status) {
        return "invoice." + switch (status) {
            case DRAFT -> "created";
            case FINANCING_AVAILABLE -> "listed_for_financing";
            default -> status.name().toLowerCase();
        };
    }

    public static String eventType(InvoiceStatus status) {
        return switch (status) {
            case DRAFT -> "InvoiceCreated";
            case SUBMITTED -> "InvoiceSubmitted";
            case BUYER_CONFIRMED -> "InvoiceBuyerConfirmed";
            case VERIFIED -> "InvoiceVerified";
            case FINANCING_AVAILABLE -> "InvoiceListedForFinancing";
            case FINANCED -> "InvoiceFinanced";
            case SETTLED -> "InvoiceSettled";
            case REJECTED -> "InvoiceRejected";
            case CANCELLED -> "InvoiceCancelled";
        };
    }
}
