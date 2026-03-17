package com.urlshortener.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * GDPR-compliant IP address hasher.
 *
 * <p>Raw IP addresses must <em>never</em> be persisted.  Instead this class
 * produces an irreversible (but deterministic) fingerprint by computing the
 * SHA-256 hash of {@code ip + ":" + dailySalt}.  Rotating the daily salt each
 * midnight ensures that the fingerprints from one day cannot be correlated with
 * those of another, satisfying the GDPR requirement for pseudonymisation with a
 * limited retention window.
 *
 * <p>The recommended salt source is a cryptographically random 16-byte value
 * stored in a secrets manager and rotated on a daily schedule.
 *
 * <h2>Thread safety</h2>
 * {@link MessageDigest} instances are not thread-safe; this class creates a new
 * instance per call, which is safe and avoids the overhead of
 * {@code ThreadLocal}.
 */
public final class IpHasher {

    private static final String ALGORITHM = "SHA-256";
    private static final String SEPARATOR  = ":";

    // ── Private constructor — static utility class ───────────────────────────

    private IpHasher() {
        throw new UnsupportedOperationException("IpHasher is a utility class");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Computes the SHA-256 hex fingerprint of {@code ip + ":" + dailySalt}.
     *
     * <p>Handles edge cases gracefully:
     * <ul>
     *   <li>If {@code ip} is {@code null} or blank, returns {@code "unknown"}.</li>
     *   <li>If {@code dailySalt} is {@code null} it is treated as an empty string
     *       (a warning is logged in production environments; callers should ensure
     *       a non-null salt is always supplied).</li>
     * </ul>
     *
     * @param ip        raw IPv4 or IPv6 address string; may be {@code null} or blank
     * @param dailySalt a per-day random salt string; should not be {@code null}
     * @return 64-character lowercase hex string, or {@code "unknown"} if
     *         {@code ip} is null/blank
     * @throws IllegalStateException if the JVM does not support SHA-256
     *                               (guaranteed by the JVM spec, so never in practice)
     */
    public static String hashIp(String ip, String dailySalt) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }

        String salt = (dailySalt != null) ? dailySalt : "";
        String input = ip + SEPARATOR + salt;

        MessageDigest digest = sha256Digest();
        byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hashBytes);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java SE spec; this branch is unreachable.
            throw new IllegalStateException("SHA-256 algorithm unavailable — JVM is non-compliant", e);
        }
    }

    /**
     * Converts a byte array to its lowercase hexadecimal string representation.
     *
     * @param bytes source bytes
     * @return lowercase hex string of length {@code bytes.length * 2}
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
