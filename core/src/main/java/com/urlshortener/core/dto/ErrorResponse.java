package com.urlshortener.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Standardised error envelope returned by all API endpoints on failure.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "errorCode": "KEY_NOT_FOUND",
 *   "message":   "No active mapping found for key: abc12345",
 *   "timestamp": "2025-06-01T12:00:00.000Z",
 *   "traceId":   "4f8a2e10-3c9d-4f1b-8e7a-6d2b9c1a0e5f"
 * }
 * }</pre>
 */
public final class ErrorResponse {

    private final String errorCode;
    private final String message;

    private final Instant timestamp;

    private final String traceId;

    @JsonCreator
    public ErrorResponse(
            @JsonProperty("errorCode") String errorCode,
            @JsonProperty("message")   String message,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("traceId")   String traceId) {
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.message   = Objects.requireNonNull(message,   "message must not be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        this.traceId   = Objects.requireNonNull(traceId,   "traceId must not be null");
    }

    // ── Static factories ──────────────────────────────────────────────────────

    /**
     * Convenience factory that sets {@code timestamp} to {@link Instant#now()}
     * and generates a random UUID as the {@code traceId}.
     */
    public static ErrorResponse of(String errorCode, String message) {
        return new ErrorResponse(errorCode, message, Instant.now(), UUID.randomUUID().toString());
    }

    /**
     * Convenience factory with an explicit {@code traceId} (e.g. propagated
     * from a distributed tracing header).
     */
    public static ErrorResponse of(String errorCode, String message, String traceId) {
        return new ErrorResponse(errorCode, message, Instant.now(), traceId);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Machine-readable error code, e.g. {@code "KEY_NOT_FOUND"},
     * {@code "RATE_LIMIT_EXCEEDED"}, {@code "MALICIOUS_URL"}.
     */
    @JsonProperty("errorCode")
    public String getErrorCode() {
        return errorCode;
    }

    /** Human-readable description of the error. */
    @JsonProperty("message")
    public String getMessage() {
        return message;
    }

    /** ISO-8601 timestamp at which the error was generated. */
    @JsonProperty("timestamp")
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Distributed-trace identifier. Correlates log entries across services.
     * Typically sourced from the {@code X-Trace-Id} or {@code traceparent} header.
     */
    @JsonProperty("traceId")
    public String getTraceId() {
        return traceId;
    }

    // ── equals / hashCode / toString ─────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ErrorResponse other)) return false;
        return Objects.equals(errorCode, other.errorCode)
                && Objects.equals(message,   other.message)
                && Objects.equals(timestamp, other.timestamp)
                && Objects.equals(traceId,   other.traceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(errorCode, message, timestamp, traceId);
    }

    @Override
    public String toString() {
        return "ErrorResponse{"
                + "errorCode='" + errorCode + '\''
                + ", message='" + message + '\''
                + ", timestamp=" + timestamp
                + ", traceId='" + traceId + '\''
                + '}';
    }
}
