package com.ifp.auth.user;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Length-based policy (NIST SP 800-63B style): long passphrases instead of composition rules.
 * The upper bound is 72 UTF-8 bytes because BCrypt ignores anything beyond that, and Spring Security
 * rejects longer inputs outright.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** @return a user-facing reason the password is unacceptable, or empty if it is acceptable. */
    public static Optional<String> violation(String password, String email) {
        if (password == null || password.isBlank()) {
            return Optional.of("must not be blank");
        }
        if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return Optional.of("must be at least " + MIN_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("must be at most " + MAX_BYTES + " bytes");
        }
        if (email != null && password.strip().toLowerCase(Locale.ROOT).equals(email.strip().toLowerCase(Locale.ROOT))) {
            return Optional.of("must not be the same as the email address");
        }
        return Optional.empty();
    }
}
