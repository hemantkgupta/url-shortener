package com.urlshortener.write.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates domain exceptions to RFC 7807 Problem Detail responses.
 *
 * <p>All error responses include a structured body with at minimum:
 * {@code type}, {@code title}, {@code status}, {@code detail}, and {@code timestamp}.
 * Validation errors additionally include a {@code fieldErrors} map.
 */
@RestControllerAdvice
public class WriteExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WriteExceptionHandler.class);

    // ── Domain exceptions ─────────────────────────────────────────────────────

    @ExceptionHandler(AliasConflictException.class)
    public ProblemDetail handleAliasConflict(AliasConflictException ex) {
        log.warn("Alias conflict: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("urn:write-service:alias-conflict"));
        pd.setTitle("Alias Already Taken");
        pd.setProperty("alias", ex.getAlias());
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(MaliciousUrlException.class)
    public ProblemDetail handleMaliciousUrl(MaliciousUrlException ex) {
        log.warn("Malicious URL rejected: {}", ex.getUrl());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("urn:write-service:malicious-url"));
        pd.setTitle("URL Rejected by Safe Browsing Policy");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(KeyGenerationException.class)
    public ProblemDetail handleKeyGeneration(KeyGenerationException ex) {
        log.error("Key generation failure: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Key generation service is temporarily unavailable");
        pd.setType(URI.create("urn:write-service:key-generation-failure"));
        pd.setTitle("Service Unavailable");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    // ── Validation exceptions ─────────────────────────────────────────────────

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        v -> v.getMessage(),
                        (a, b) -> a,
                        LinkedHashMap::new));

        log.debug("Constraint violation: {}", fieldErrors);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setType(URI.create("urn:write-service:validation-error"));
        pd.setTitle("Bad Request");
        pd.setProperty("fieldErrors", fieldErrors);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));

        log.debug("Method argument not valid: {}", fieldErrors);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body validation failed");
        pd.setType(URI.create("urn:write-service:validation-error"));
        pd.setTitle("Bad Request");
        pd.setProperty("fieldErrors", fieldErrors);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    // ── Catch-all ─────────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unhandled exception in Write Service", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        pd.setType(URI.create("urn:write-service:internal-error"));
        pd.setTitle("Internal Server Error");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
