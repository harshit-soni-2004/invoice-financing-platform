package com.ifp.invoice.service;

public class DuplicateInvoiceNumberException extends RuntimeException {

    public DuplicateInvoiceNumberException(String invoiceNumber) {
        super("You already have an invoice numbered " + invoiceNumber);
    }
}
