package com.ifp.auth.web;

import com.ifp.auth.user.RegistrationService;
import com.ifp.auth.user.UserAccount;
import com.ifp.auth.web.dto.RegistrationRequest;
import com.ifp.auth.web.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    @PreAuthorize("permitAll()")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegistrationRequest request) {
        UserAccount account = registrationService.register(request.email(), request.password(), request.displayName(),
                request.role());
        return UserResponse.from(account);
    }
}
