package com.ifp.auth.web.dto;

import com.ifp.auth.user.Role;
import com.ifp.auth.user.UserAccount;
import com.ifp.auth.user.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, String displayName, Role role, UserStatus status, Instant createdAt) {

    public static UserResponse from(UserAccount account) {
        return new UserResponse(account.getId(), account.getEmail(), account.getDisplayName(), account.getRole(),
                account.getStatus(), account.getCreatedAt());
    }
}
