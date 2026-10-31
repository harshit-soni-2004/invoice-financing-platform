package com.ifp.auth.web;

import com.ifp.auth.user.AccountService;
import com.ifp.auth.user.Role;
import com.ifp.auth.user.UserAdminService;
import com.ifp.auth.user.UserStatus;
import com.ifp.auth.web.dto.ChangePasswordRequest;
import com.ifp.auth.web.dto.PageResponse;
import com.ifp.auth.web.dto.RejectUserRequest;
import com.ifp.auth.web.dto.UpdateProfileRequest;
import com.ifp.auth.web.dto.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Identity always comes from the validated token's {@code sub}; no endpoint accepts a caller-supplied user ID for "me". */
@RestController
public class UserController {

    private final UserAdminService userAdminService;
    private final AccountService accountService;

    public UserController(UserAdminService userAdminService, AccountService accountService) {
        this.userAdminService = userAdminService;
        this.accountService = accountService;
    }

    @GetMapping("/api/users/me")
    @PreAuthorize("isAuthenticated()")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(userAdminService.get(UUID.fromString(jwt.getSubject())));
    }

    @PutMapping("/api/users/me")
    @PreAuthorize("isAuthenticated()")
    public UserResponse updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return UserResponse.from(accountService.updateProfile(UUID.fromString(jwt.getSubject()), request.displayName()));
    }

    @PostMapping("/api/users/me/password")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeMyPassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        accountService.changePassword(UUID.fromString(jwt.getSubject()), request.currentPassword(), request.newPassword());
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

    @PostMapping("/api/admin/users/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse reject(@PathVariable UUID id, @RequestBody(required = false) @Valid RejectUserRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        String reason = request == null ? null : request.reason();
        return UserResponse.from(userAdminService.reject(id, UUID.fromString(jwt.getSubject()), reason));
    }
}
