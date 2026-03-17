package com.urlshortener.core.exception;

/**
 * Thrown when the key-generation service fails to produce a new short key.
 *
 * <p>Typical causes include:
 * <ul>
 *   <li>Failure to reach the distributed counter (e.g. ZooKeeper / etcd is
 *       unavailable).</li>
 *   <li>Counter range exhaustion (astronomically unlikely for a 62^8 space).</li>
 *   <li>Unexpected encoding errors.</li>
 * </ul>
 *
 * <p>Callers should translate this exception into an HTTP 503 Service
 * Unavailable response and may retry with exponential back-off.
 */
public final class KeyGenerationException extends RuntimeException {

    /**
     * Constructs a new {@code KeyGenerationException} with a descriptive
     * message and the root cause.
     *
     * @param message a human-readable explanation of what went wrong; must not
     *                be {@code null}
     * @param cause   the underlying exception that triggered the failure; may
     *                be {@code null} if there is no root cause
     */
    public KeyGenerationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code KeyGenerationException} with a descriptive
     * message and no root cause.
     *
     * @param message a human-readable explanation of what went wrong
     */
    public KeyGenerationException(String message) {
        super(message);
    }
}
