package com.ifp.auth.web.dto;

import com.ifp.auth.user.PasswordPolicy;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Applies {@link PasswordPolicy} to a registration request, reporting the violation on the {@code password} field. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AcceptablePassword.Validator.class)
public @interface AcceptablePassword {

    String message() default "is not acceptable";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<AcceptablePassword, RegistrationRequest> {

        @Override
        public boolean isValid(RegistrationRequest request, ConstraintValidatorContext context) {
            if (request == null) {
                return true;
            }
            return PasswordPolicy.violation(request.password(), request.email())
                    .map(reason -> {
                        context.disableDefaultConstraintViolation();
                        context.buildConstraintViolationWithTemplate(reason).addPropertyNode("password").addConstraintViolation();
                        return false;
                    })
                    .orElse(true);
        }
    }
}
