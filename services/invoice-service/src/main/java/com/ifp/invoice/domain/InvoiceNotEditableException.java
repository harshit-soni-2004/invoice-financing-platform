package com.ifp.invoice.domain;

public class InvoiceNotEditableException extends InvoiceConflictException {

    public InvoiceNotEditableException(InvoiceStatus status) {
        super("Only DRAFT invoices can be edited; this invoice is " + status);
    }
}
