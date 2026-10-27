package com.ifp.invoice.web;

/** The endpoint requires If-Match to prevent lost updates (HTTP 428). */
public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException() {
        super("This request requires an If-Match header with the invoice's current ETag");
    }
}
