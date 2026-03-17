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
    private final int limitPerSecond;

    /**
     * Constructs a new {@code RateLimitException}.
     *
     * @param clientId       identifier of the throttled client (e.g. API key,
     *                       IP address); must not be {@code null}
     * @param limitPerSecond the configured rate limit that was exceeded (requests
     *                       per second); must be positive
     */
    public RateLimitException(String clientId, int limitPerSecond) {
        super("Client '" + clientId + "' has exceeded the rate limit of "
              + limitPerSecond + " request(s) per second");
        this.clientId       = clientId;
        this.limitPerSecond = limitPerSecond;
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
     * Returns the rate limit (requests per second) that was exceeded.
     *
     * @return configured limit in requests per second
     */
    public int getLimitPerSecond() {
        return limitPerSecond;
    }
}
