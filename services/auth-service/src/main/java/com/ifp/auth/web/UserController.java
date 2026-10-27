package com.ifp.auth.web;

import com.ifp.auth.user.Role;
import com.ifp.auth.user.UserAdminService;
import com.ifp.auth.user.UserStatus;
import com.ifp.auth.web.dto.PageResponse;
import com.ifp.auth.web.dto.UserResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Identity always comes from the validated token's {@code sub}; no endpoint accepts a caller-supplied user ID for "me". */
@RestController
public class UserController {

    private final UserAdminService userAdminService;

    public UserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping("/api/users/me")
    @PreAuthorize("isAuthenticated()")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(userAdminService.get(UUID.fromString(jwt.getSubject())));
    }

    @GetMapping("/api/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<UserResponse> list(@RequestParam(required = false) Role role,
                                           @RequestParam(required = false) UserStatus status,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        return PageResponse.from(userAdminService.list(role, status, pageable), UserResponse::from);
    }

    @PostMapping("/api/admin/users/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(userAdminService.activate(id, UUID.fromString(jwt.getSubject())));
    }
}
