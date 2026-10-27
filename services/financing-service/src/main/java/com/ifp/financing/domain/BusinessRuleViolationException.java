package com.ifp.financing.domain;

/** A request that is well-formed but breaks a financing rule (HTTP 422). */
public class BusinessRuleViolationException extends RuntimeException {

    private final String code;

    public BusinessRuleViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
