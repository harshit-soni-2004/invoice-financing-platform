package com.ifp.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The only self-service profile field; email, role and status cannot be changed by the user. */
public record UpdateProfileRequest(@NotBlank @Size(max = 100) String displayName) {
}
