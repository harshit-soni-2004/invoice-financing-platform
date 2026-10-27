package com.ifp.auth.user;

public class UserNotPendingApprovalException extends RuntimeException {

    private final UserStatus currentStatus;

    public UserNotPendingApprovalException(UserStatus currentStatus) {
        super("Only accounts pending approval can be activated");
        this.currentStatus = currentStatus;
    }

    public UserStatus currentStatus() {
        return currentStatus;
    }
}
