package com.ifp.invoice.service;

import java.util.UUID;

/** Also used when the invoice exists but belongs to another seller, so its existence is not revealed. */
public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(UUID id) {
        super("Invoice " + id + " was not found");
    }
}
