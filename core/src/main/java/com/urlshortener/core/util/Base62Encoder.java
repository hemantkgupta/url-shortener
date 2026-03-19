package com.urlshortener.core.util;

/**
 * Stateless Base62 encoder / decoder used to convert distributed counters
 * (e.g. from a key-generation service) into compact, URL-safe short keys.
 *
 * <h2>Alphabet</h2>
 * {@code 0-9 A-Z a-z} (62 characters, indices 0..61) — digits first, then
 * upper-case, then lower-case.  This ordering matches the natural sort order
 * expected by some analytics queries and is the de-facto standard for URL
 * shortener key spaces.
 *
 * <h2>Key length</h2>
 * {@link #encode(long)} always returns exactly {@value #KEY_LENGTH} characters
 * (zero-padded on the left with {@code '0'}).  Eight Base62 digits represent
 * values up to 62^8 − 1 ≈ 218 trillion — more than sufficient for a
 * hyperscale URL shortener.
 *
 * <h2>Generated public keys</h2>
 * {@link #toShortKey(long)} maps a raw monotonic counter into the public
 * 8-character Base62 range {@code [10000000, ZZZZZZZZ]} via a deterministic
 * permutation.  This keeps all generated keys a fixed length, avoids leading
 * zero padding, and prevents the public key from exposing the raw counter
 * directly.
 *
 * <h2>Thread safety</h2>
 * All methods are stateless and therefore inherently thread-safe.
 */
public final class Base62Encoder {

    /** Base62 alphabet: {@code 0-9 A-Z a-z}. */
    public static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final char[] ALPHABET_CHARS = ALPHABET.toCharArray();

    /** Number of characters in the alphabet — the encoding base. */
    private static final int BASE = ALPHABET_CHARS.length; // 62

    /**
     * Fixed output length for {@link #encode(long)}.
     *
     * <p>62^8 − 1 = 218 340 105 584 895 ≈ 218 trillion distinct keys.
     */
    public static final int KEY_LENGTH = 8;

    /** Pre-computed reverse lookup: ASCII code → Base62 digit value, or -1. */
    private static final int[] CHAR_TO_VALUE = buildReverseTable();

    /** Smallest value that encodes to exactly 8 Base62 characters: {@code 62^7}. */
    private static final long SHORT_KEY_FLOOR = minFixedLengthValue(KEY_LENGTH);

    /**
     * Number of usable generated-key values in the public 8-character range
     * {@code [10000000, ZZZZZZZZ]}.
     */
    private static final long SHORT_KEY_SPACE = maxEncodableValue() - SHORT_KEY_FLOOR + 1;

    /**
     * Multiplier for the public-key permutation.
     *
     * <p>Must be coprime to {@link #SHORT_KEY_SPACE} so the mapping remains a
     * bijection over the entire 8-character public range.
     */
    private static final long SHORT_KEY_MULTIPLIER = 40_009L;

    /** Additive shift for the public-key permutation. */
    private static final long SHORT_KEY_OFFSET = 13_579_246_801L;

    static {
        if (gcd(SHORT_KEY_MULTIPLIER, SHORT_KEY_SPACE) != 1L) {
            throw new ExceptionInInitializerError(
                    "SHORT_KEY_MULTIPLIER must be coprime to SHORT_KEY_SPACE");
        }
    }

    // ── Private constructor — static utility class ───────────────────────────

    private Base62Encoder() {
        throw new UnsupportedOperationException("Base62Encoder is a utility class");
    }

    // ── Core encode / decode ──────────────────────────────────────────────────

    /**
     * Encodes a non-negative counter to a Base62 string padded to exactly
     * {@value #KEY_LENGTH} characters (left-padded with {@code '0'}).
     *
     * @param counter a non-negative counter value; must not exceed
     *                {@code 62^KEY_LENGTH - 1} (≈ 218 trillion)
     * @return exactly {@value #KEY_LENGTH} Base62 characters
     * @throws IllegalArgumentException if {@code counter} is negative or
     *                                  exceeds the representable range
     */
    public static String encode(long counter) {
        if (counter < 0) {
            throw new IllegalArgumentException(
                    "counter must be non-negative, got: " + counter);
        }

        char[] buf = new char[KEY_LENGTH];
        long remaining = counter;

        // Fill buffer from right (least-significant digit) to left.
        for (int i = KEY_LENGTH - 1; i >= 0; i--) {
            buf[i] = ALPHABET_CHARS[(int) (remaining % BASE)];
            remaining /= BASE;
        }

        if (remaining != 0) {
            throw new IllegalArgumentException(
                    "counter value " + counter
                    + " exceeds the maximum representable value for KEY_LENGTH="
                    + KEY_LENGTH + " (max=" + maxEncodableValue() + ")");
        }

        return new String(buf);
    }

    /**
     * Encodes {@code value} as a zero-padded Base62 string of at least
     * {@code minLength} characters.  If the encoded representation is longer
     * than {@code minLength} the full string is returned without truncation.
     *
     * <p>This overload is used by services that need a length other than the
     * default {@value #KEY_LENGTH}.
     *
     * @param value     non-negative number to encode
     * @param minLength minimum number of output characters (≥ 1)
     * @return Base62 encoded string of at least {@code minLength} chars
     * @throws IllegalArgumentException if {@code value} is negative or
     *                                  {@code minLength} is less than 1
     */
    public static String encode(long value, int minLength) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "value must be non-negative, got: " + value);
        }
        if (minLength < 1) {
            throw new IllegalArgumentException(
                    "minLength must be >= 1, got: " + minLength);
        }

        // 13 chars safely covers Long.MAX_VALUE in Base62.
        char[] buf = new char[Math.max(minLength, 13)];
        int pos = buf.length;
        long remaining = value;

        do {
            buf[--pos] = ALPHABET_CHARS[(int) (remaining % BASE)];
            remaining /= BASE;
        } while (remaining > 0);

        int contentLen = buf.length - pos;
        if (contentLen >= minLength) {
            return new String(buf, pos, contentLen);
        }
        int paddingNeeded = minLength - contentLen;
        char[] result = new char[minLength];
        java.util.Arrays.fill(result, 0, paddingNeeded, '0');
        System.arraycopy(buf, pos, result, paddingNeeded, contentLen);
        return new String(result);
    }

    /**
     * Decodes a Base62-encoded string back to its original {@code long} value.
     *
     * <p>Accepts both padded (exactly {@value #KEY_LENGTH} chars) and
     * un-padded representations.
     *
     * @param key a non-null, non-empty string of Base62 characters
     * @return the decoded non-negative {@code long} value
     * @throws IllegalArgumentException if {@code key} is null, empty, or
     *                                  contains characters outside the Base62 alphabet
     */
    public static long decode(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be null or empty");
        }

        long result = 0;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            int value = (c < CHAR_TO_VALUE.length) ? CHAR_TO_VALUE[c] : -1;
            if (value < 0) {
                throw new IllegalArgumentException(
                        "Illegal character '" + c + "' at index " + i
                        + " in Base62 key: \"" + key + '"');
            }
            result = result * BASE + value;
        }
        return result;
    }

    // ── Hotspot-prevention helper ─────────────────────────────────────────────

    /**
     * Reverses the lowest {@code bits} bits of {@code value}.
     *
     * <p>Used for B-tree / LSM write-hotspot prevention: by reversing the bit
     * pattern of a monotonically increasing counter before encoding, inserts
     * are distributed across the full key space instead of clustering at one
     * end of the index.
     *
     * <p>Example with {@code bits = 4}:
     * <pre>
     *   bitReverse(0b0001, 4) → 0b1000  (1 → 8)
     *   bitReverse(0b0010, 4) → 0b0100  (2 → 4)
     *   bitReverse(0b1010, 4) → 0b0101  (10 → 5)
     * </pre>
     *
     * <p>Example with {@code bits = 40}: counter values 0, 1, 2, 3 map to
     * 0, 549 755 813 888, 274 877 906 944, 824 633 720 832 — spread uniformly
     * across the 40-bit space.
     *
     * @param value the input value; only the lowest {@code bits} bits are
     *              considered, higher bits are masked to zero
     * @param bits  number of lowest bits to reverse; must be in [1, 63]
     * @return a non-negative long with the lowest {@code bits} bits reversed
     * @throws IllegalArgumentException if {@code bits} is outside [1, 63]
     */
    public static long bitReverse(long value, int bits) {
        if (bits < 1 || bits > 63) {
            throw new IllegalArgumentException(
                    "bits must be in [1, 63], got: " + bits);
        }

        long result = 0L;
        long v = value;
        for (int i = 0; i < bits; i++) {
            result = (result << 1) | (v & 1L);
            v >>= 1;
        }
        return result;
    }

    /**
     * Converts a raw monotonic counter into a public 8-character short key.
     *
     * <p>The mapping is a deterministic affine permutation over the exact
     * 8-character Base62 range {@code [10000000, ZZZZZZZZ]}.  That gives three
     * properties that the generated public URL key needs:
     * <ul>
     *   <li>exactly 8 Base62 characters, every time</li>
     *   <li>no leading-zero padding such as {@code 00000NPc}</li>
     *   <li>no obvious one-step relationship between adjacent raw counters</li>
     * </ul>
     *
     * <p>This permutation is intentionally lightweight and deterministic for
     * distributed services.  It is not a cryptographic construction.
     *
     * @param counter raw monotonic counter value
     * @return exactly 8 Base62 characters in the public key range
     * @throws IllegalArgumentException if {@code counter} is negative or exceeds
     *                                  the supported 8-character public key space
     */
    public static String toShortKey(long counter) {
        if (counter < 0) {
            throw new IllegalArgumentException(
                    "counter must be non-negative, got: " + counter);
        }
        if (counter >= SHORT_KEY_SPACE) {
            throw new IllegalArgumentException(
                    "counter value " + counter
                    + " exceeds the supported 8-character short-key space (max="
                    + (SHORT_KEY_SPACE - 1) + ")");
        }

        long permuted = (counter * SHORT_KEY_MULTIPLIER + SHORT_KEY_OFFSET) % SHORT_KEY_SPACE;
        return encode(SHORT_KEY_FLOOR + permuted);
    }

    // ── Package-private helpers ───────────────────────────────────────────────

    /**
     * Returns the maximum counter value that {@link #encode(long)} can represent
     * with exactly {@value #KEY_LENGTH} digits: {@code 62^KEY_LENGTH − 1}.
     */
    static long maxEncodableValue() {
        long max = 1;
        for (int i = 0; i < KEY_LENGTH; i++) {
            max *= BASE;
        }
        return max - 1;
    }

    static long minFixedLengthValue(int length) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1, got: " + length);
        }

        long value = 1L;
        for (int i = 1; i < length; i++) {
            value *= BASE;
        }
        return value;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static long gcd(long a, long b) {
        long x = Math.abs(a);
        long y = Math.abs(b);
        while (y != 0L) {
            long next = x % y;
            x = y;
            y = next;
        }
        return x;
    }

    private static int[] buildReverseTable() {
        // ASCII printable characters fit within 128; Base62 uses only [0-9A-Za-z].
        int[] table = new int[128];
        java.util.Arrays.fill(table, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            table[ALPHABET.charAt(i)] = i;
        }
        return table;
    }
}
