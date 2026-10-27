package com.ifp.auth.web;

import com.ifp.auth.user.DuplicateEmailException;
import com.ifp.auth.user.UserNotFoundException;
import com.ifp.auth.user.UserNotPendingApprovalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Maps API errors to RFC 9457 problem details. Messages never include stack traces, SQL, passwords or token
 * contents, and rejected field values are never echoed back.
 */
@RestControllerAdvice(basePackages = "com.ifp.auth.web")
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_FAILED",
                "One or more fields are invalid");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> Map.of("field", fieldName(error),
                        "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DuplicateEmailException.class)
    ProblemDetail duplicateEmail(DuplicateEmailException ex) {
        return problem(HttpStatus.CONFLICT, "Email already registered", "DUPLICATE_EMAIL", ex.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail userNotFound(UserNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "User not found", "USER_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(UserNotPendingApprovalException.class)
    ProblemDetail notPending(UserNotPendingApprovalException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "User not pending approval", "USER_NOT_PENDING_APPROVAL",
                ex.getMessage());
        problem.setProperty("currentStatus", ex.currentStatus());
        return problem;
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ProblemDetail concurrentModification(RuntimeException ex) {
        log.info("Concurrent modification detected: {}", ex.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "Concurrent modification", "CONCURRENT_MODIFICATION",
                "The user was changed by another request. Reload it and try again.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthenticated(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "UNAUTHENTICATED", "Authentication is required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "ACCESS_DENIED", "You do not have permission to perform this action");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "INTERNAL_ERROR", "An unexpected error occurred");
    }

    /** {@code isRoleSelfRegistrable} is reported as {@code role}, so clients see the field they actually sent. */
    private static String fieldName(FieldError error) {
        return "roleSelfRegistrable".equals(error.getField()) ? "role" : error.getField();
    }

    private static ProblemDetail problem(HttpStatus status, String title, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
