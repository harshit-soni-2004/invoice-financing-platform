package com.ifp.auth.user;

import com.ifp.auth.security.ratelimit.LoginAttemptLimiter;
import com.ifp.messaging.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Changes a signed-in user makes to their own account. Email, role and status are not self-service: the email is the
 * sign-in identity, and role and status are decided at registration or by an administrator.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    public static final String USER_PROFILE_UPDATED = "user.profile-updated";
    public static final String USER_PASSWORD_CHANGED = "user.password-changed";
    /** Lockout scope for wrong current passwords, kept apart from the sign-in form's per-address lockouts. */
    static final String PASSWORD_CHANGE_SCOPE = "password-change";

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter limiter;
    private final Clock clock;
    private final OutboxWriter outbox;

    public AccountService(UserAccountRepository users, PasswordEncoder passwordEncoder, LoginAttemptLimiter limiter,
                          Clock clock, OutboxWriter outbox) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.limiter = limiter;
        this.clock = clock;
        this.outbox = outbox;
    }

    /** notification-service keeps its own copy of the name for email greetings, so a rename is published. */
    @Transactional
    public UserAccount updateProfile(UUID userId, String displayName) {
        UserAccount account = users.findById(userId).orElseThrow(UserNotFoundException::new);
        if (account.rename(displayName, clock.instant())) {
            users.saveAndFlush(account);
            outbox.append(USER_PROFILE_UPDATED, "UserProfileUpdated", "USER", account.getId(), account.getId(),
                    new UserProfileUpdated(account.getId(), account.getEmail(), account.getDisplayName(),
                            account.getRole().name()));
            log.info("User profile updated: id={}", account.getId());
        }
        return account;
    }

    /**
     * Requires the current password, so a borrowed access token alone cannot take over the account. Wrong current
     * passwords count towards a lockout per user, like failed sign-ins.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        String key = userId.toString();
        Optional<Duration> wait = limiter.checkLockout(PASSWORD_CHANGE_SCOPE, key);
        if (wait.isPresent()) {
            throw new TooManyAttemptsException(wait.get());
        }
        UserAccount account = users.findById(userId).orElseThrow(UserNotFoundException::new);
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            limiter.recordFailure(PASSWORD_CHANGE_SCOPE, key);
            throw new AccountFieldException("currentPassword", "is incorrect");
        }
        limiter.recordSuccess(PASSWORD_CHANGE_SCOPE, key);
        Optional<String> violation = PasswordPolicy.violation(newPassword, account.getEmail());
        if (violation.isPresent()) {
            throw new AccountFieldException("newPassword", violation.get());
        }
        if (passwordEncoder.matches(newPassword, account.getPasswordHash())) {
            throw new AccountFieldException("newPassword", "must be different from the current password");
        }
        account.changePasswordHash(passwordEncoder.encode(newPassword), clock.instant());
        users.saveAndFlush(account);
        outbox.append(USER_PASSWORD_CHANGED, "UserPasswordChanged", "USER", account.getId(), account.getId(),
                new UserPasswordChanged(account.getId()));
        log.info("User password changed: id={}", account.getId());
    }

    public record UserProfileUpdated(UUID userId, String email, String displayName, String role) {
    }

    /** Carries no secret; audit-service records that the change happened. */
    public record UserPasswordChanged(UUID userId) {
    }
}
