package com.ifp.auth.user;

import com.ifp.messaging.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    public static final String USER_REGISTERED = "user.registered";

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final OutboxWriter outbox;

    public RegistrationService(UserAccountRepository users, PasswordEncoder passwordEncoder, Clock clock, OutboxWriter outbox) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.outbox = outbox;
    }

    /** The password must already satisfy {@link PasswordPolicy}; the request DTO validates it. */
    @Transactional
    public UserAccount register(String email, String password, String displayName, Role role) {
        if (!role.isSelfRegistrable()) {
            throw new IllegalArgumentException("Role cannot be self-registered");
        }
        String normalizedEmail = UserAccount.normalizeEmail(email);
        if (users.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException();
        }
        UserAccount account = UserAccount.selfRegistered(UUID.randomUUID(), normalizedEmail,
                passwordEncoder.encode(password), displayName, role, clock.instant());
        try {
            users.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent sign-ups with the same email: the unique key decides the winner.
            if (e.getMessage() != null && e.getMessage().contains("uk_users_email")) {
                throw new DuplicateEmailException();
            }
            throw e;
        }
        outbox.append(USER_REGISTERED, "UserRegistered", "USER", account.getId(), account.getId(),
                new UserRegistered(account.getId(), account.getEmail(), account.getDisplayName(), account.getRole().name()));
        log.info("User registered: id={} role={} status={}", account.getId(), account.getRole(), account.getStatus());
        return account;
    }

    /** The only event that carries contact details; notification-service needs them to address mail. */
    public record UserRegistered(UUID userId, String email, String displayName, String role) {
    }
}
