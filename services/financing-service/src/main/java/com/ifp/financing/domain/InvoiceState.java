package com.ifp.financing.domain;

/** Invoice status as published by invoice-service. Only FINANCING_AVAILABLE invoices can receive offers. */
public enum InvoiceState {
    DRAFT,
    SUBMITTED,
    BUYER_CONFIRMED,
    VERIFIED,
    FINANCING_AVAILABLE,
    FINANCED,
    SETTLED,
    REJECTED,
    CANCELLED
}
