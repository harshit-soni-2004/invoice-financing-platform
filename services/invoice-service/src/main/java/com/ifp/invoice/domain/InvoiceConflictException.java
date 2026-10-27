package com.ifp.invoice.domain;

/** The request is valid but conflicts with the invoice's current state. */
public abstract class InvoiceConflictException extends RuntimeException {

    protected InvoiceConflictException(String message) {
        super(message);
    }
}
