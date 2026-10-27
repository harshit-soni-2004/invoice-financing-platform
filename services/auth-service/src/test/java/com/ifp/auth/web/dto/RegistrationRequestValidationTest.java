package com.ifp.auth.web.dto;

import com.ifp.auth.user.Role;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void validSellerRequestHasNoViolations() {
        assertThat(violations(new RegistrationRequest("asha@example.test", "correct horse battery", "Asha", Role.SELLER)))
                .isEmpty();
    }

    @Test
    void adminRoleIsRejected() {
        assertThat(violations(new RegistrationRequest("asha@example.test", "correct horse battery", "Asha", Role.ADMIN)))
                .containsEntry("roleSelfRegistrable", "must be SELLER or FINANCIER");
    }

    @Test
    void passwordViolationIsReportedOnPasswordField() {
        assertThat(violations(new RegistrationRequest("asha@example.test", "short", "Asha", Role.SELLER)))
                .containsEntry("password", "must be at least 12 characters");
    }

    @Test
    void requiredFieldsAndEmailFormatAreChecked() {
        Map<String, String> violations = violations(new RegistrationRequest("not-an-email", "correct horse battery", " ", null));

        assertThat(violations).containsKeys("email", "displayName", "role");
    }

    @Test
    void toStringNeverContainsThePassword() {
        assertThat(new RegistrationRequest("asha@example.test", "correct horse battery", "Asha", Role.SELLER).toString())
                .doesNotContain("correct horse battery");
    }

    private static Map<String, String> violations(RegistrationRequest request) {
        return validator.validate(request).stream()
                .collect(Collectors.toMap(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage, (a, b) -> a));
    }
}
