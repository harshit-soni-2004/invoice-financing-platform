package com.ifp.auth.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional first administrator, supplied by the operator through environment variables. Nothing is created when
 * either value is blank, and no administrator credentials exist in the repository.
 */
@ConfigurationProperties("auth.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password, String displayName) {

    public boolean isConfigured() {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }

    public String displayNameOrDefault() {
        return displayName == null || displayName.isBlank() ? "Platform Administrator" : displayName;
    }
}
