package com.urlshortener.write.exception;

/**
 * Thrown when a caller requests a custom alias (vanity key) that is already
 * in use in the {@code alias_mapping} table.
 */
public class AliasConflictException extends RuntimeException {

    private final String alias;

    public AliasConflictException(String alias) {
        super("Custom alias '" + alias + "' is already taken");
        this.alias = alias;
    }

    public String getAlias() {
        return alias;
    }
}
