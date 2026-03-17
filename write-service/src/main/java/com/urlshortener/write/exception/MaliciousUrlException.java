package com.urlshortener.write.exception;

/**
 * Thrown when the Safe Browsing check determines that the submitted URL
 * appears on the configured blocklist.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity — the request was syntactically
 * valid but cannot be processed due to a policy violation.
 */
public class MaliciousUrlException extends RuntimeException {

    private final String url;

    public MaliciousUrlException(String url) {
        super("URL was rejected by Safe Browsing policy: " + url);
        this.url = url;
    }

    public String getUrl() {
        return url;
    }
}
