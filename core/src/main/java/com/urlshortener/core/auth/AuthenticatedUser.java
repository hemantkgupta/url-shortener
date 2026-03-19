package com.urlshortener.core.auth;

/**
 * Canonical authenticated-user view shared across services.
 *
 * <p>The numeric {@code userId} is a stable hash of the identity provider subject and
 * is suitable for storage in the existing Cassandra {@code bigint} columns.
 */
public record AuthenticatedUser(
        long userId,
        String subject,
        String email,
        String displayName) {
}
