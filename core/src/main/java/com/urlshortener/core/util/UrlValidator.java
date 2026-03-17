package com.urlshortener.core.util;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Stateless URL validation utility.
 *
 * <p>Validation rules enforced by {@link #validateUrl(String)}:
 * <ol>
 *   <li>The URL must not be {@code null}.</li>
 *   <li>The URL must not be blank (empty or whitespace-only).</li>
 *   <li>The URL must not exceed {@value #MAX_URL_LENGTH} characters.</li>
 *   <li>The URL must be a syntactically valid absolute URI.</li>
 *   <li>The scheme must be {@code http} or {@code https}.</li>
 *   <li>The URI must have a non-empty host component.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * All methods are stateless and therefore inherently thread-safe.
 */
public final class UrlValidator {

    /** Maximum accepted URL length (inclusive), matching most browser limits. */
    public static final int MAX_URL_LENGTH = 2048;

    // ── Private constructor — static utility class ───────────────────────────

    private UrlValidator() {
        throw new UnsupportedOperationException("UrlValidator is a utility class");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Validates {@code url} according to the rules described in the class
     * Javadoc.  Returns normally if the URL is valid.
     *
     * @param url the URL string to validate; may be {@code null}
     * @throws IllegalArgumentException with a descriptive message if any
     *                                  validation rule is violated
     */
    public static void validateUrl(String url) {
        if (url == null) {
            throw new IllegalArgumentException("URL must not be null");
        }
        if (url.isBlank()) {
            throw new IllegalArgumentException("URL must not be blank");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "URL exceeds maximum allowed length of " + MAX_URL_LENGTH
                    + " characters (actual length: " + url.length() + ")");
        }

        URI uri;
        try {
            uri = new URI(url).parseServerAuthority();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "URL is not a valid URI: \"" + url + "\" — " + e.getReason(), e);
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException(
                    "URL must have a scheme (http or https): \"" + url + '"');
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "URL scheme must be 'http' or 'https', got '" + scheme
                    + "' in: \"" + url + '"');
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "URL must have a non-empty host component: \"" + url + '"');
        }
    }

    /**
     * Returns {@code true} if the host of {@code url} matches {@code ownDomain},
     * indicating that the shortened URL would redirect back to the shortener
     * itself — a circular redirect.
     *
     * <p>The comparison is case-insensitive.  Both {@code url} and
     * {@code ownDomain} are treated leniently: if either is {@code null} or
     * {@code url} is not a valid URI, the method returns {@code false} rather
     * than throwing.
     *
     * <p>Examples (assuming {@code ownDomain = "sho.rt"}):
     * <pre>
     *   isCircularRedirect("https://sho.rt/abc",         "sho.rt") → true
     *   isCircularRedirect("https://SHO.RT/path",        "sho.rt") → true
     *   isCircularRedirect("https://www.sho.rt/abc",     "sho.rt") → false
     *   isCircularRedirect("https://example.com/abc",    "sho.rt") → false
     * </pre>
     *
     * @param url       the URL to inspect; may be {@code null}
     * @param ownDomain the domain of this URL shortener service, e.g. {@code "sho.rt"}
     * @return {@code true} if the URL points back at {@code ownDomain}
     */
    public static boolean isCircularRedirect(String url, String ownDomain) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (ownDomain == null || ownDomain.isBlank()) {
            return false;
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return false;
        }

        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        return host.equalsIgnoreCase(ownDomain.trim());
    }
}
