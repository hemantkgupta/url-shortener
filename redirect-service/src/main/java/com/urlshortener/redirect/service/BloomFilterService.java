package com.urlshortener.redirect.service;

import com.urlshortener.redirect.config.RedirectServiceProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gates every redirect request through a Bloom filter (RedisBloom / Redisson) to
 * immediately reject short keys that were never created — without touching ScyllaDB.
 *
 * <p>The Bloom filter is populated by the Write Service whenever a new short URL is
 * created (via a Kafka consumer that calls {@code BF.ADD}).  It intentionally has a
 * small false-positive rate (typically 0.1%), meaning a tiny fraction of non-existent
 * keys will pass through to the DB lookup.  False negatives are impossible by design,
 * so no valid redirect is ever incorrectly blocked.
 *
 * <h2>Fail-open strategy</h2>
 * If the RedisBloom module is unavailable (network timeout, Redis restart, etc.) this
 * service returns {@code true} (key may exist) to let the request proceed to the DB.
 * This preserves availability at the cost of slightly elevated DB load during outages.
 *
 * <h2>Metrics</h2>
 * {@code redirect.bloom_filter.rejects} (counter) — incremented each time the filter
 * definitively rejects a key, enabling alerting on unexpected spike patterns.
 */
@Component
public class BloomFilterService {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterService.class);

    private final RedissonClient redissonClient;
    private final String bloomFilterKey;
    private final Counter rejectCounter;

    public BloomFilterService(
            RedissonClient redissonClient,
            RedirectServiceProperties properties,
            MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.bloomFilterKey = properties.getRedis().getBloomFilterKey();
        this.rejectCounter = Counter.builder("redirect.bloom_filter.rejects")
                .description("Number of short keys definitively rejected by the Bloom filter")
                .register(meterRegistry);
    }

    /**
     * Returns {@code true} if the Bloom filter indicates the key <em>may</em> exist,
     * or {@code false} if it definitively does not exist.
     *
     * <p>A return value of {@code true} does NOT guarantee the key is in the database;
     * it merely means the request should proceed to the cache / DB lookup.
     *
     * @param shortKey the Base-62 short key to check (e.g. {@code "aB3xY9z"})
     * @return {@code true} if possibly present (proceed), {@code false} if definitely absent (404)
     */
    public boolean exists(String shortKey) {
        try {
            RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(bloomFilterKey);
            boolean present = bloomFilter.contains(shortKey);
            if (!present) {
                rejectCounter.increment();
                log.debug("Bloom filter rejected shortKey={}", shortKey);
            }
            return present;
        } catch (Exception ex) {
            // Fail-open: do not block valid redirects when RedisBloom is unavailable
            log.warn("RedisBloom unavailable — failing open for shortKey={}: {}",
                    shortKey, ex.getMessage());
            return true;
        }
    }
}
