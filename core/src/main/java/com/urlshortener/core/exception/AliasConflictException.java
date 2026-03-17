package com.urlshortener.core.exception;

/**
 * Thrown when a caller requests a custom alias (vanity key) that is already
 * in use by another mapping.
 *
 * <p>Callers (e.g. the write service) should translate this exception into
 * an HTTP 409 Conflict response.
 */
public final class AliasConflictException extends RuntimeException {

    private final String alias;

    /**
     * Constructs a new {@code AliasConflictException} for the given alias.
     *
     * @param alias the custom short key that is already taken; must not be
     *              {@code null}
     */
    public AliasConflictException(String alias) {
        super("Custom alias '" + alias + "' is already in use; choose a different key");
        this.alias = alias;
    }

    /**
     * Returns the conflicting alias.
     *
     * @return the alias that caused the conflict
     */
    public String getAlias() {
        return alias;
    }
}
