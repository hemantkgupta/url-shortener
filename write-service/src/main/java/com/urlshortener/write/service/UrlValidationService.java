package com.urlshortener.write.service;

import com.urlshortener.write.config.WriteServiceProperties;
import com.urlshortener.write.exception.MaliciousUrlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates and normalises incoming long URLs before they are shortened.
 *
 * <h2>Checks performed (in order)</h2>
 * <ol>
 *   <li>Null / blank guard</li>
 *   <li>Length ≤ 2 048 characters</li>
 *   <li>Syntactic validity via {@link URI} parsing</li>
 *   <li>Scheme must be {@code http} or {@code https}</li>
 *   <li>Circular-redirect detection — host must not equal {@code write.own-domain}</li>
 *   <li>Safe Browsing stub — if {@code write.safe-browsing.enabled=true}, checks
 *       the host against the configured in-memory {@code blocklist}</li>
 * </ol>
 *
 * <p>If all checks pass the URL is normalised (scheme + host lowercased, path/query
 * preserved as-is) and returned to the caller.
 */
@Service
public class UrlValidationService {

    private static final Logger log = LoggerFactory.getLogger(UrlValidationService.class);

    private static final int MAX_URL_LENGTH = 2_048;

    private final WriteServiceProperties properties;
    private final Set<String> blocklist;

    public UrlValidationService(WriteServiceProperties properties) {
        this.properties = properties;
        // Build an immutable-ish set from the configured list at construction time;
        // the list is only loaded once, which is fine for a local-dev stub.
        this.blocklist = new HashSet<>(properties.getSafeBrowsing().getBlocklist());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Validates and normalises {@code rawUrl}.
     *
     * @param rawUrl the URL submitted by the caller; must not be null
     * @return the normalised URL (scheme + host in lower case)
     * @throws IllegalArgumentException if the URL is null, blank, too long, or syntactically invalid
     * @throws IllegalStateException    if a circular redirect is detected
     * @throws MaliciousUrlException    if Safe Browsing is enabled and the host is blocked
     */
    public String validateAndNormalize(String rawUrl) {
        checkNotBlank(rawUrl);
        checkLength(rawUrl);

        URI uri = parse(rawUrl);
        checkScheme(uri);
        checkCircularRedirect(uri);
        checkSafeBrowsing(uri);

        return normalize(uri);
    }

    /**
     * Returns {@code true} if the host of {@code rawUrl} matches the configured
     * {@code write.own-domain}.  Exposed for testing.
     *
     * @param rawUrl the URL to inspect
     * @return {@code true} if the URL points back at this service's own domain
     */
    public boolean isCircularRedirect(String rawUrl) {
        try {
            URI uri = new URI(rawUrl);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            return host.equalsIgnoreCase(properties.getOwnDomain());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void checkNotBlank(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("longUrl must not be null or blank");
        }
    }

    private void checkLength(String url) {
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "longUrl exceeds maximum allowed length of " + MAX_URL_LENGTH + " characters");
        }
    }

    private URI parse(String url) {
        try {
            URI uri = new URI(url);
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("longUrl must be an absolute URI: " + url);
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("longUrl is not a valid URI: " + ex.getMessage(), ex);
        }
    }

    private void checkScheme(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "longUrl must use http or https scheme, got: " + scheme);
        }
    }

    private void checkCircularRedirect(URI uri) {
        String host = uri.getHost();
        if (host != null && host.equalsIgnoreCase(properties.getOwnDomain())) {
            throw new IllegalStateException(
                    "Circular redirect detected: longUrl points to this service's own domain '"
                    + properties.getOwnDomain() + "'");
        }
    }

    private void checkSafeBrowsing(URI uri) {
        if (!properties.getSafeBrowsing().isEnabled()) {
            return;
        }

        String host = uri.getHost();
        if (host != null && blocklist.contains(host.toLowerCase())) {
            log.warn("Safe Browsing: URL blocked — host={}", host);
            throw new MaliciousUrlException(uri.toString());
        }
    }

    private String normalize(URI uri) {
        // Lowercase scheme and host; rebuild without re-encoding path/query
        String scheme = uri.getScheme().toLowerCase();
        String host   = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
        int    port   = uri.getPort();

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (port != -1) {
            sb.append(':').append(port);
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            sb.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            sb.append('#').append(uri.getRawFragment());
        }
        return sb.toString();
    }
}
