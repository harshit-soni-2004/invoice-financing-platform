package com.ifp.invoice.domain;

/** Well-formed input that breaks a business rule (for example, a due date too close to maturity). */
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
