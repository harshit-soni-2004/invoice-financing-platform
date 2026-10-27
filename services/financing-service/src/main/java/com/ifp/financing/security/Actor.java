package com.ifp.financing.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, derived only from a validated access token: {@code sub} is the user ID and the
 * {@code roles} claim provides the roles. Request bodies and parameters never influence it.
 */
public record Actor(UUID userId, Set<Role> roles) {

    public Actor {
        Objects.requireNonNull(userId);
        roles = roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
    }

    public static Actor from(Authentication authentication) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            Arrays.stream(Role.values())
                    .filter(role -> role.authority().equals(authority.getAuthority()))
                    .findFirst()
                    .ifPresent(roles::add);
        }
        return new Actor(UUID.fromString(authentication.getName()), roles);
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return has(Role.ADMIN);
    }

    public void require(Role role) {
        if (!has(role)) {
            throw new AccessDeniedException("Requires role " + role);
        }
    }

    /** Role recorded in history entries; ADMIN wins if a user somehow holds several. */
    public String primaryRole() {
        for (Role role : new Role[]{Role.ADMIN, Role.FINANCIER, Role.SELLER}) {
            if (has(role)) {
                return role.name();
            }
        }
        return "NONE";
    }
}
