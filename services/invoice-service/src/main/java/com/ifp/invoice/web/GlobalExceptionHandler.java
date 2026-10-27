package com.ifp.invoice.web;

import com.ifp.invoice.domain.BusinessRuleViolationException;
import com.ifp.invoice.domain.InvalidStatusTransitionException;
import com.ifp.invoice.domain.InvoiceConflictException;
import com.ifp.invoice.service.DuplicateInvoiceNumberException;
import com.ifp.invoice.service.InvoiceNotFoundException;
import com.ifp.invoice.service.PreconditionFailedException;
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

/** Maps errors to RFC 9457 problem details. Messages never include stack traces, SQL, or token contents. */
@RestControllerAdvice
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
                .map(error -> Map.of("field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvoiceNotFoundException.class)
    ProblemDetail notFound(InvoiceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Invoice not found", "INVOICE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    ProblemDetail invalidTransition(InvalidStatusTransitionException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Invalid status transition", "INVALID_STATUS_TRANSITION", ex.getMessage());
        problem.setProperty("currentStatus", ex.from());
        problem.setProperty("requestedStatus", ex.to());
        return problem;
    }

    @ExceptionHandler(InvoiceConflictException.class)
    ProblemDetail conflict(InvoiceConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Invoice state conflict", "INVOICE_NOT_EDITABLE", ex.getMessage());
    }

    @ExceptionHandler(DuplicateInvoiceNumberException.class)
    ProblemDetail duplicate(DuplicateInvoiceNumberException ex) {
        return problem(HttpStatus.CONFLICT, "Duplicate invoice number", "DUPLICATE_INVOICE_NUMBER", ex.getMessage());
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ProblemDetail concurrentModification(RuntimeException ex) {
        log.info("Concurrent modification detected: {}", ex.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "Concurrent modification", "CONCURRENT_MODIFICATION",
                "The invoice was changed by another request. Reload it and try again.");
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ProblemDetail businessRule(BusinessRuleViolationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violated", ex.code(), ex.getMessage());
    }

    @ExceptionHandler(PreconditionFailedException.class)
    ProblemDetail preconditionFailed(PreconditionFailedException ex) {
        ProblemDetail problem = problem(HttpStatus.PRECONDITION_FAILED, "Precondition failed", "VERSION_MISMATCH", ex.getMessage());
        if (ex.currentVersion() != null) {
            problem.setProperty("currentETag", ETags.of(ex.currentVersion()));
        }
        return problem;
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail preconditionRequired(PreconditionRequiredException ex) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "Precondition required", "IF_MATCH_REQUIRED", ex.getMessage());
    }

    @ExceptionHandler(InvalidQueryParameterException.class)
    ProblemDetail badQueryParameter(InvalidQueryParameterException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "INVALID_QUERY_PARAMETER", ex.getMessage());
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

    private static ProblemDetail problem(HttpStatus status, String title, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
