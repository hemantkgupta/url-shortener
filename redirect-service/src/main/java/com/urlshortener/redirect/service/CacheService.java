package com.urlshortener.redirect.service;

import com.urlshortener.redirect.config.RedirectServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reactive Redis cache layer for URL mappings, built on Lettuce via
 * {@link ReactiveRedisTemplate}.
 *
 * <h2>XFetch early-refresh</h2>
 * To avoid a thundering herd when cache entries expire simultaneously,
 * {@link #shouldEarlyRefresh(String, long)} implements a probabilistic early-expiry
 * algorithm (PER / XFetch).  As an entry's remaining TTL shrinks, the probability of
 * triggering an early refresh grows, so that under load the cache is re-warmed by one
 * request before expiry rather than by thousands of concurrent cache misses.
 *
 * <p>The approximation used here is:
 * <pre>{@code
 * shouldRefresh = (remainingTtl < ttlSeconds * 0.1 * random())
 * }</pre>
 * This is cheaper to compute than the full XFetch formula while exhibiting the same
 * desirable staggering behaviour.
 *
 * <h2>Key naming</h2>
 * Cache keys follow the pattern {@code url:{shortKey}}, controlled by
 * {@code redirect.redis.url-key-prefix} in {@code application.yml}.
 */
@Component
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final String urlKeyPrefix;
    private final double xfetchBeta;

    public CacheService(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
            RedirectServiceProperties properties) {
        this.redisTemplate = redisTemplate;
        this.urlKeyPrefix = properties.getRedis().getUrlKeyPrefix();
        this.xfetchBeta = properties.getRedis().getXfetchBeta();
    }

    /**
     * Looks up the long URL for a short key from Redis.
     *
     * @param shortKey the Base-62 short key
     * @return a {@link Mono} emitting the long URL, or {@link Mono#empty()} on cache miss
     */
    public Mono<String> get(String shortKey) {
        String cacheKey = urlKeyPrefix + shortKey;
        return redisTemplate.opsForValue().get(cacheKey)
                .doOnNext(url -> log.debug("Cache HIT for shortKey={}", shortKey))
                .doOnSuccess(url -> {
                    if (url == null) {
                        log.debug("Cache MISS for shortKey={}", shortKey);
                    }
                });
    }

    /**
     * Stores the long URL in Redis with the given TTL.
     *
     * <p>The SET is performed asynchronously; callers subscribe or chain without blocking.
     *
     * @param shortKey   the Base-62 short key
     * @param longUrl    the destination URL to cache
     * @param ttlSeconds how long (in seconds) the entry should live in Redis
     * @return a {@link Mono<Void>} that completes when the SET succeeds
     */
    public Mono<Void> set(String shortKey, String longUrl, long ttlSeconds) {
        String cacheKey = urlKeyPrefix + shortKey;
        return redisTemplate.opsForValue()
                .set(cacheKey, longUrl, Duration.ofSeconds(ttlSeconds))
                .doOnSuccess(ok -> log.debug("Cached shortKey={} ttl={}s", shortKey, ttlSeconds))
                .then();
    }

    /**
     * Determines whether the cache entry should be proactively refreshed from the DB
     * before it expires, using a probabilistic XFetch approximation.
     *
     * <p>As the remaining TTL approaches zero the probability of returning {@code true}
     * increases, causing a single in-flight request to trigger an early refresh.
     * Remaining TTL is fetched synchronously via a 10 ms bounded {@code block()} because
     * this is called from a virtual-thread context where blocking is cheap.
     *
     * @param shortKey   the Base-62 short key
     * @param ttlSeconds the original TTL set for this entry (used to compute the window)
     * @return {@code true} if the entry should be refreshed proactively
     */
    public boolean shouldEarlyRefresh(String shortKey, long ttlSeconds) {
        try {
            String cacheKey = urlKeyPrefix + shortKey;
            Duration remaining = redisTemplate.getExpire(cacheKey)
                    .block(Duration.ofMillis(10));

            if (remaining == null || remaining.isNegative()) {
                // Key doesn't exist or has no TTL — treat as a miss
                return false;
            }

            long remainingSeconds = remaining.getSeconds();

            // XFetch approximation:
            // Trigger early refresh probabilistically as remaining TTL shrinks to 10%
            // of the original. Math.random() staggers which request "wins" the refresh.
            double threshold = ttlSeconds * 0.1 * Math.random() * xfetchBeta;
            boolean refresh = remainingSeconds < threshold;

            if (refresh) {
                log.debug("XFetch early-refresh triggered for shortKey={} remainingTtl={}s",
                        shortKey, remainingSeconds);
            }
            return refresh;
        } catch (Exception ex) {
            // If we cannot determine TTL, don't trigger early refresh — let normal expiry handle it
            log.warn("Unable to determine TTL for shortKey={}: {}", shortKey, ex.getMessage());
            return false;
        }
    }
}
