package com.ifp.financing.domain;

public class InvalidStatusTransitionException extends RuntimeException {

    private final String from;
    private final String to;

    public InvalidStatusTransitionException(String entity, Enum<?> from, Enum<?> to) {
        super("Cannot move " + entity + " from " + from + " to " + to);
        this.from = from.name();
        this.to = to.name();
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }
}
