package com.ifp.financing.web;

/** Malformed request metadata (query parameters, headers): HTTP 400. */
class InvalidRequestException extends RuntimeException {

    private final String code;

    InvalidRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
