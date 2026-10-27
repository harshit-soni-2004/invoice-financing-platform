package com.ifp.auth.web.dto;

import com.ifp.auth.user.Role;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@AcceptablePassword
public record RegistrationRequest(
        @NotBlank @Email @Size(max = 254) String email,
        String password,
        @NotBlank @Size(max = 100) String displayName,
        @NotNull Role role) {

    @AssertTrue(message = "must be SELLER or FINANCIER")
    public boolean isRoleSelfRegistrable() {
        return role == null || role.isSelfRegistrable();
    }

    /** Keeps the password out of logs and exception messages if the record is ever printed. */
    @Override
    public String toString() {
        return "RegistrationRequest[email=" + email + ", displayName=" + displayName + ", role=" + role + "]";
    }
}
