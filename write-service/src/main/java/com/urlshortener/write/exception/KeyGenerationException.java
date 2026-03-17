package com.urlshortener.write.exception;

/**
 * Thrown when the write-service cannot obtain a key block from the
 * Key Generation Service (KGS) — typically due to a network failure
 * or KGS being unavailable.
 *
 * <p>Maps to HTTP 503 Service Unavailable.
 */
public class KeyGenerationException extends RuntimeException {

    public KeyGenerationException(String message) {
        super(message);
    }

    public KeyGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
