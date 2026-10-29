package com.ifp.auth.user;

public enum UserStatus {
    ACTIVE,
    /** Registered but waiting for an administrator; cannot sign in. */
    PENDING_APPROVAL,
    DISABLED,
    /** Turned down by an administrator while pending approval. Terminal: can never sign in or be activated. */
    REJECTED
}
