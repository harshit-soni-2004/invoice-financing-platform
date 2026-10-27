package com.ifp.auth.user;

public enum UserStatus {
    ACTIVE,
    /** Registered but waiting for an administrator; cannot sign in. */
    PENDING_APPROVAL,
    DISABLED
}
