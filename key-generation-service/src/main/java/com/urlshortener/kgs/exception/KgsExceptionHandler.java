package com.urlshortener.kgs.exception;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.urlshortener.kgs.service.BlockAllocator.BlockAllocationException;

/**
 * Centralised exception handler for all KGS REST controllers.
 *
 * <p>Maps domain-specific and unexpected exceptions to appropriate HTTP
 * response codes and a consistent JSON error envelope so that callers
 * (write-service pods) can distinguish transient infrastructure failures
 * from logic errors.
 *
 * <h2>Response envelope</h2>
 * <pre>
 * {
 *   "status":    503,
 *   "error":     "Service Unavailable",
 *   "message":   "...",
 *   "timestamp": "2024-01-01T00:00:00Z"
 * }
 * </pre>
 */
@RestControllerAdvice
public class KgsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(KgsExceptionHandler.class);

    // ── etcd / allocation failures → 503 ─────────────────────────────────────

    /**
     * Handles {@link BlockAllocationException}, which wraps etcd connectivity
     * or CAS-retry exhaustion failures.
     *
     * <p>A 503 tells the write-service to back off and retry against a
     * different KGS pod (via load-balancer round-robin).
     */
    @ExceptionHandler(BlockAllocationException.class)
    public ResponseEntity<Map<String, Object>> handleBlockAllocationException(
            BlockAllocationException ex) {
        log.error("Block allocation failed — etcd may be unavailable: {}", ex.getMessage(), ex);
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    // ── Catch-all → 500 ───────────────────────────────────────────────────────

    /**
     * Safety net for any unexpected exception not covered by a more specific
     * handler.  Returns 500 with a generic message so that internal details are
     * not leaked to callers.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unhandled exception in KGS controller: {}", ex.getMessage(), ex);
        return errorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please check the KGS logs.");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus status, String message) {
        Map<String, Object> body = Map.of(
                "status",    status.value(),
                "error",     status.getReasonPhrase(),
                "message",   message == null ? "No detail available" : message,
                "timestamp", Instant.now().toString());

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
