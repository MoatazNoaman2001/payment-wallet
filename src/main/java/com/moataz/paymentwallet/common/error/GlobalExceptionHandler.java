package com.moataz.paymentwallet.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One place that turns exceptions into RFC 7807 responses (Content-Type:
 * application/problem+json). Without this, Bean Validation failures come back as a
 * wall of Spring's internal detail, and anything unexpected leaks a stack trace.
 *
 * ProblemDetail is built into Spring Framework 6+ — no extra library needed.
 * The shape is standard:
 *   { "type": ..., "title": ..., "status": ..., "detail": ..., "instance": ... }
 * plus any custom properties you attach.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Fired when a @Valid @RequestBody fails Bean Validation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://paymentwallet.local/errors/validation"));
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid");

        // field -> message, so the client can highlight the offending input
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
          .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Malformed JSON, or an enum value that is not one of the constants. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Malformed request body");
        problem.setDetail("The request body could not be parsed");
        return problem;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Resource not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /** Bad or missing credentials. */
    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Not authenticated");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Authenticated, but not yours. Covers AuthorizationDeniedException from @PreAuthorize.
     * The detail is deliberately vague: telling someone an account exists but is not theirs
     * still leaks that it exists.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Access denied");
        problem.setDetail("You do not have access to this resource");
        return problem;
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Business rule violated");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicate(DuplicateResourceException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Resource already exists");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * The safety net: a UNIQUE or CHECK constraint the service check missed, e.g. two
     * requests registering the same email at the same instant. The database is the
     * last line of defence and it does not lose races.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleConstraint(DataIntegrityViolationException ex) {
        log.warn("Database constraint violated", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Constraint violation");
        problem.setDetail("The request conflicts with data that already exists");
        return problem;
    }

    /** Anything unhandled: log the real cause, tell the client nothing useful to an attacker. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal error");
        problem.setDetail("Unexpected error, see server logs");
        return problem;
    }
}
