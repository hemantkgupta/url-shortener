package com.urlshortener.core.exception;

/**
 * Thrown when a short key is looked up in the data store but no active mapping
 * is found — either because the key never existed, has been deactivated, or has
 * expired.
 *
 * <p>Callers (e.g. the redirect service) should translate this exception into
 * an HTTP 404 Not Found response.
 */
public final class KeyNotFoundException extends RuntimeException {

    private final String shortKey;

    /**
     * Constructs a new {@code KeyNotFoundException} for the given short key.
     *
     * @param shortKey the Base62 key that could not be resolved; must not be
     *                 {@code null}
     */
    public KeyNotFoundException(String shortKey) {
        super("No active mapping found for short key: '" + shortKey + "'");
        this.shortKey = shortKey;
    }

    /**
     * Returns the short key that triggered this exception.
     *
     * @return the unresolvable short key
     */
    public String getShortKey() {
        return shortKey;
    }
}
