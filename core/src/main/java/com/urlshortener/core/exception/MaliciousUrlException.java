package com.urlshortener.core.exception;

/**
 * Thrown when a submitted URL has been identified as malicious (e.g. it
 * appears on a phishing or malware blocklist, or triggers heuristic detection).
 *
 * <p>Callers should translate this exception into an HTTP 422 Unprocessable
 * Entity or HTTP 400 Bad Request response, and may wish to record the incident
 * for abuse-monitoring purposes.
 *
 * <p>The offending URL is included in the exception for logging; implementors
 * must take care <em>not</em> to echo raw user-supplied URLs back in API
 * responses without sanitisation.
 */
public final class MaliciousUrlException extends RuntimeException {

    private final String url;

    /**
     * Constructs a new {@code MaliciousUrlException} for the given URL.
     *
     * @param url the URL that was flagged as malicious; must not be {@code null}
     */
    public MaliciousUrlException(String url) {
        super("URL has been flagged as malicious and cannot be shortened: '" + url + "'");
        this.url = url;
    }

    /**
     * Returns the URL that triggered this exception.
     *
     * <p><strong>Warning:</strong> do not include this value in API error
     * responses without sanitisation — it is raw user input.
     *
     * @return the malicious URL
     */
    public String getUrl() {
        return url;
    }
}
