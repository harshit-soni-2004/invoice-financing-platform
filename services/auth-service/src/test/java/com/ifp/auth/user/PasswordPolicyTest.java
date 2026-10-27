package com.ifp.auth.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    private static final String EMAIL = "asha@example.test";

    @Test
    void acceptsLongPassphrase() {
        assertThat(PasswordPolicy.violation("correct horse battery", EMAIL)).isEmpty();
    }

    @Test
    void rejectsBlankAndShortPasswords() {
        assertThat(PasswordPolicy.violation(null, EMAIL)).contains("must not be blank");
        assertThat(PasswordPolicy.violation("            ", EMAIL)).contains("must not be blank");
        assertThat(PasswordPolicy.violation("elevenchars", EMAIL)).contains("must be at least 12 characters");
        assertThat(PasswordPolicy.violation("twelve chars", EMAIL)).isEmpty();
    }

    @Test
    void countsCharactersNotUtf16Units() {
        // 12 emoji = 12 characters but 24 UTF-16 units and 48 UTF-8 bytes.
        assertThat(PasswordPolicy.violation("\uD83D\uDD12".repeat(11), EMAIL)).contains("must be at least 12 characters");
        assertThat(PasswordPolicy.violation("\uD83D\uDD12".repeat(12), EMAIL)).isEmpty();
    }

    @Test
    void enforcesBcryptByteLimit() {
        assertThat(PasswordPolicy.violation("a".repeat(72), EMAIL)).isEmpty();
        assertThat(PasswordPolicy.violation("a".repeat(73), EMAIL)).contains("must be at most 72 bytes");
        // 25 three-byte characters = 75 bytes, although only 25 characters.
        assertThat(PasswordPolicy.violation("\u20B9".repeat(25), EMAIL)).contains("must be at most 72 bytes");
    }

    @Test
    void rejectsPasswordEqualToEmail() {
        assertThat(PasswordPolicy.violation(" Asha@Example.TEST ", EMAIL)).contains("must not be the same as the email address");
    }
}
