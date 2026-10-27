package com.ifp.financing.domain;

import java.util.UUID;

/** Also used when the resource exists but the caller is not a party to it, so its existence is not revealed. */
public class ResourceNotFoundException extends RuntimeException {

    private final String code;

    private ResourceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static ResourceNotFoundException invoice(UUID id) {
        return new ResourceNotFoundException("INVOICE_NOT_FOUND", "Invoice " + id + " is not available for financing");
    }

    public static ResourceNotFoundException offer(UUID id) {
        return new ResourceNotFoundException("OFFER_NOT_FOUND", "Offer " + id + " was not found");
    }

    public static ResourceNotFoundException agreement(UUID id) {
        return new ResourceNotFoundException("AGREEMENT_NOT_FOUND", "Agreement " + id + " was not found");
    }

    public static ResourceNotFoundException disbursement(UUID agreementId) {
        return new ResourceNotFoundException("DISBURSEMENT_NOT_FOUND", "Agreement " + agreementId + " has not been disbursed");
    }

    public String code() {
        return code;
    }
}
