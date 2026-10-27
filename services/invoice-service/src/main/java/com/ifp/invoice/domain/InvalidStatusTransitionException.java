package com.ifp.invoice.domain;

public class InvalidStatusTransitionException extends InvoiceConflictException {

    private final InvoiceStatus from;
    private final InvoiceStatus to;

    public InvalidStatusTransitionException(InvoiceStatus from, InvoiceStatus to) {
        super("Invoice cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public InvoiceStatus from() {
        return from;
    }

    public InvoiceStatus to() {
        return to;
    }
}
