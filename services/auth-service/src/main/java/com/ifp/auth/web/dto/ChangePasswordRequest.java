package com.ifp.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** The new password is checked against the password policy in the service, which knows the account's email. */
public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {

    /** Keeps both passwords out of logs and exception messages if the record is ever printed. */
    @Override
    public String toString() {
        return "ChangePasswordRequest[***]";
    }
}
