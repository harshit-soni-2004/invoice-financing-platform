package com.ifp.invoice.service;

/**
 * Version a client expects the invoice to have, taken from If-Match. {@link #ANY} means no precondition
 * (header absent on optional endpoints, or "*"); concurrent writes are still caught by optimistic locking.
 */
public record ExpectedVersion(Long value) {

    public static final ExpectedVersion ANY = new ExpectedVersion(null);

    public static ExpectedVersion of(long value) {
        return new ExpectedVersion(value);
    }

    public void check(long currentVersion) {
        if (value != null && value != currentVersion) {
            throw new PreconditionFailedException(currentVersion);
        }
    }
}
