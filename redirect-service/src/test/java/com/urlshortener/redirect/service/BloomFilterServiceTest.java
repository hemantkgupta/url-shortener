package com.urlshortener.redirect.service;

import com.urlshortener.redirect.config.RedirectServiceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BloomFilterService}.
 *
 * <p>Uses a {@link SimpleMeterRegistry} so that counter assertions work without a
 * full Spring context.
 */
@ExtendWith(MockitoExtension.class)
class BloomFilterServiceTest {

    private static final String BLOOM_FILTER_KEY = "url:bloom";
    private static final String SHORT_KEY = "aB3xY9z";

    @Mock
    private RedissonClient redissonClient;

    @SuppressWarnings("unchecked")
    @Mock
    private RBloomFilter<String> bloomFilter;

    private SimpleMeterRegistry meterRegistry;
    private BloomFilterService bloomFilterService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        RedirectServiceProperties properties = new RedirectServiceProperties();
        RedirectServiceProperties.Redis redisProps = new RedirectServiceProperties.Redis();
        redisProps.setBloomFilterKey(BLOOM_FILTER_KEY);
        properties.setRedis(redisProps);

        doReturn(bloomFilter).when(redissonClient).getBloomFilter(BLOOM_FILTER_KEY);

        bloomFilterService = new BloomFilterService(redissonClient, properties, meterRegistry);
    }

    @Test
    @DisplayName("key in Bloom filter → returns true")
    void existsReturnsTrueWhenKeyIsInFilter() {
        when(bloomFilter.contains(SHORT_KEY)).thenReturn(true);

        boolean result = bloomFilterService.exists(SHORT_KEY);

        assertThat(result).isTrue();
        verify(bloomFilter).contains(SHORT_KEY);
    }

    @Test
    @DisplayName("key not in Bloom filter → returns false and increments reject counter")
    void existsReturnsFalseWhenKeyNotInFilter() {
        when(bloomFilter.contains(SHORT_KEY)).thenReturn(false);

        boolean result = bloomFilterService.exists(SHORT_KEY);

        assertThat(result).isFalse();
        assertThat(meterRegistry.counter("redirect.bloom_filter.rejects").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("RedisBloom exception → fail-open (returns true), counter not incremented")
    void existsFailsOpenWhenRedisBloomThrowsException() {
        when(bloomFilter.contains(anyString()))
                .thenThrow(new RuntimeException("RedisBloom unavailable"));

        boolean result = bloomFilterService.exists(SHORT_KEY);

        // Fail-open: must not block a potentially valid redirect
        assertThat(result).isTrue();

        // Reject counter must NOT be incremented for fail-open (it measures definitive rejects)
        assertThat(meterRegistry.counter("redirect.bloom_filter.rejects").count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("reject counter accumulates across multiple rejected keys")
    void rejectCounterAccumulatesAcrossMultipleRejects() {
        when(bloomFilter.contains(anyString())).thenReturn(false);

        bloomFilterService.exists("key1");
        bloomFilterService.exists("key2");
        bloomFilterService.exists("key3");

        assertThat(meterRegistry.counter("redirect.bloom_filter.rejects").count())
                .isEqualTo(3.0);
    }
}
