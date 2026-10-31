package com.ifp.auth.user;

/** A self-service change refused because of one field, reported like a validation error on that field. */
public class AccountFieldException extends RuntimeException {

    private final String field;

    public AccountFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
