package com.urlshortener.write.service;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous helper that pre-warms the Redis read-through cache and populates
 * the RedisBloom filter after a URL is successfully persisted to ScyllaDB.
 *
 * <p>Both operations are fire-and-forget from the write path's perspective:
 * failures are logged as warnings but never surfaced to the HTTP caller.
 * The read-service handles a cache miss gracefully by falling back to ScyllaDB.
 */
@Component
public class CacheWarmupService {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmupService.class);

    /** Redis key prefix for URL mappings — must match the redirect-service convention. */
    private static final String URL_KEY_PREFIX = "url:";

    /** Bloom filter name in Redis — shared between write and read services. */
    private static final String BLOOM_FILTER_NAME = "url:bloom";

    /**
     * Expected insertions for the Bloom filter; size it for ~1 billion URLs
     * with a 0.1 % false-positive rate.
     */
    private static final long  BLOOM_EXPECTED_INSERTIONS = 1_000_000_000L;
    private static final double BLOOM_FPP                = 0.001;

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RBloomFilter<String>                  bloomFilter;

    public CacheWarmupService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            RedissonClient redissonClient) {
        this.redisTemplate = redisTemplate;
        // Lazily initialise — tryInit is idempotent if already configured
        this.bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_NAME);
        try {
            this.bloomFilter.tryInit(BLOOM_EXPECTED_INSERTIONS, BLOOM_FPP);
        } catch (Exception ex) {
            log.warn("Bloom filter tryInit skipped (likely already initialised): {}", ex.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Asynchronously sets {@code url:{shortKey} → longUrl} in Redis with the
     * given TTL.  A TTL of {@code 0} means no expiry.
     *
     * @param shortKey   the short key
     * @param longUrl    the destination URL
     * @param ttlSeconds expiry in seconds (0 = no expiry)
     * @return a {@link CompletableFuture} that completes when the SET is acknowledged
     */
    public CompletableFuture<Void> warmCache(String shortKey, String longUrl, long ttlSeconds) {
        String redisKey = URL_KEY_PREFIX + shortKey;
        Duration ttl = ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds) : Duration.ZERO;

        var setOp = ttlSeconds > 0
                ? redisTemplate.opsForValue().set(redisKey, longUrl, ttl)
                : redisTemplate.opsForValue().set(redisKey, longUrl);

        return setOp
                .doOnSuccess(ok -> log.debug("Cache warmed: key={}, ttlSeconds={}", redisKey, ttlSeconds))
                .doOnError(ex -> log.warn("Cache warm failed for key={}: {}", redisKey, ex.getMessage()))
                .onErrorComplete()    // fire-and-forget: swallow errors
                .toFuture()
                .thenApply(v -> null);
    }

    /**
     * Asynchronously adds {@code shortKey} to the RedisBloom filter so that the
     * redirect-service can skip ScyllaDB lookups for unknown keys.
     *
     * <p>Executed on a virtual-thread pool via {@link CompletableFuture#runAsync}.
     *
     * @param shortKey the short key to add
     * @return a {@link CompletableFuture} that completes when the BF.ADD is done
     */
    public CompletableFuture<Void> addToBloomFilter(String shortKey) {
        return CompletableFuture.runAsync(() -> {
            try {
                bloomFilter.add(shortKey);
                log.debug("Bloom filter updated: shortKey={}", shortKey);
            } catch (Exception ex) {
                log.warn("Bloom filter add failed for shortKey={}: {}", shortKey, ex.getMessage());
            }
        });
    }
}
