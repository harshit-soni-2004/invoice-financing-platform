package com.ifp.auth.user;

public enum Role {
    SELLER,
    FINANCIER,
    ADMIN;

    /** Roles a visitor may choose at public registration. ADMIN is never self-assignable. */
    public boolean isSelfRegistrable() {
        return this != ADMIN;
    }

    public String authority() {
        return "ROLE_" + name();
    }
}
