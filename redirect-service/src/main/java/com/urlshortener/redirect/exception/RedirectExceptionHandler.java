package com.urlshortener.redirect.exception;

import com.urlshortener.core.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global exception handler for the Redirect Service.
 *
 * <p>Translates unhandled exceptions into structured {@link ErrorResponse} JSON
 * bodies so that clients receive a consistent error envelope regardless of the
 * internal failure mechanism.
 *
 * <p>Expected errors (NotFound, Gone, invalid key format) are handled explicitly in
 * {@link com.urlshortener.redirect.controller.RedirectController} and never reach
 * this handler.  This handler covers truly unexpected conditions:
 * infrastructure outages, programming errors, or Spring MVC exceptions.
 */
@RestControllerAdvice
public class RedirectExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RedirectExceptionHandler.class);

    /**
     * Handles type mismatch exceptions (e.g. illegal path variable types).
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        log.debug("Type mismatch: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        "BAD_REQUEST",
                        "Invalid request parameter: " + ex.getName()));
    }

    /**
     * Handles Spring MVC's no-resource-found exception (e.g. accessing a path that
     * doesn't map to any controller).  Returns 404 Not Found.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", "No resource found at the requested path"));
    }

    /**
     * Catch-all handler for any unhandled {@link Exception}.
     *
     * <p>The exception is logged at ERROR level with a full stack trace so that
     * on-call engineers can investigate.  The response body deliberately omits internal
     * details to avoid leaking implementation information to clients.
     *
     * @param ex the unhandled exception
     * @return HTTP 500 with a generic {@link ErrorResponse}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error in redirect service", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred. Please try again later."));
    }
}
