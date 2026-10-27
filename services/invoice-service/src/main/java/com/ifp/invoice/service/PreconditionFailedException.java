package com.ifp.invoice.service;

/** The client's If-Match does not match the current version (HTTP 412). */
public class PreconditionFailedException extends RuntimeException {

    private final Long currentVersion;

    public PreconditionFailedException(Long currentVersion) {
        super("The invoice has been modified since you last read it");
        this.currentVersion = currentVersion;
    }

    /** {@code null} when the If-Match value could not be parsed. */
    public Long currentVersion() {
        return currentVersion;
    }
}
