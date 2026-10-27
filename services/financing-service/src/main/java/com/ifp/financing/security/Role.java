package com.ifp.financing.security;

public enum Role {
    SELLER,
    FINANCIER,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
