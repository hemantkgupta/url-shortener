package com.urlshortener.core.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Produces a stable positive 64-bit identifier from an external identity subject.
 *
 * <p>This keeps the persistence schema compatible with the existing {@code bigint}
 * owner columns while supporting string-based identity providers such as Google.
 */
public final class UserIdentityHasher {

    private UserIdentityHasher() {
        throw new UnsupportedOperationException("UserIdentityHasher is a utility class");
    }

    public static long hashSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }

        byte[] digest = sha256(subject.trim());
        long raw = ByteBuffer.wrap(digest).getLong();
        return raw == Long.MIN_VALUE ? 0L : Math.abs(raw);
    }

    private static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
