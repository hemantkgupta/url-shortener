package com.urlshortener.core.exception;

/**
 * Thrown when a client exceeds its configured request rate limit.
 *
 * <p>Callers (e.g. the write or redirect service) should translate this
 * exception into an HTTP 429 Too Many Requests response, and should include a
 * {@code Retry-After} header where possible.
 */
public final class RateLimitException extends RuntimeException {

    private final String clientId;
    private final int limit;
    private final long windowSeconds;

    /**
     * Constructs a new {@code RateLimitException}.
     *
     * @param clientId       identifier of the throttled client (e.g. API key,
     *                       IP address); must not be {@code null}
     * @param limit the configured rate limit that was exceeded; must be positive
     * @param windowSeconds the time window in seconds that the limit applies to
     */
    public RateLimitException(String clientId, int limit, long windowSeconds) {
        super("Client '" + clientId + "' has exceeded the rate limit of "
              + limit + " request(s) per " + windowSeconds + " second window");
        this.clientId = clientId;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Returns the identifier of the client that was rate-limited.
     *
     * @return client identifier (API key, IP, etc.)
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * Returns the rate limit that was exceeded.
     *
     * @return configured limit in requests per window
     */
    public int getLimit() {
        return limit;
    }

    /**
     * Returns the time window in seconds that the limit applies to.
     *
     * @return time window in seconds
     */
    public long getWindowSeconds() {
        return windowSeconds;
    }
}
