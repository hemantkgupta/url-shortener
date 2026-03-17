package com.urlshortener.redirect.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for the Redirect Service.
 *
 * <p>Two separate Redis clients are configured intentionally:
 * <ul>
 *   <li><strong>Lettuce</strong> ({@link ReactiveRedisTemplate}) — non-blocking async pipeline
 *       for URL cache lookups (GET) and stores (SET EX).  Drives {@code CacheService}.</li>
 *   <li><strong>Redisson</strong> ({@link RedissonClient}) — used exclusively to call
 *       {@code BF.EXISTS} on the RedisBloom filter (Redisson ships a first-class
 *       {@code RBloomFilter} API that speaks the RedisBloom module protocol).</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    private final RedirectServiceProperties properties;

    public RedisConfig(RedirectServiceProperties properties) {
        this.properties = properties;
    }

    /**
     * Lettuce connection factory used by {@link ReactiveRedisTemplate}.
     *
     * <p>Spring Boot's auto-configured {@code spring.data.redis.*} properties drive the
     * host and port, but we declare an explicit bean so that the Redisson client can use
     * its own separate connection without sharing the Lettuce pool.
     */
    @Bean
    public LettuceConnectionFactory lettuceConnectionFactory() {
        String host = properties.getRedis() != null ? "localhost" : "localhost";
        // Host/port resolved from spring.data.redis.* auto-config; we rely on the
        // Spring Boot auto-configured factory injected as ReactiveRedisConnectionFactory.
        // This explicit factory declaration exists to document the intent clearly.
        return new LettuceConnectionFactory();
    }

    /**
     * Fully non-blocking {@link ReactiveRedisTemplate} wired with pure String serialisers.
     *
     * <p>Using {@link StringRedisSerializer} for both key and value avoids the
     * Java-serialisation overhead and keeps Redis memory efficient (no type metadata prefix).
     */
    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {

        StringRedisSerializer serializer = StringRedisSerializer.UTF_8;

        RedisSerializationContext<String, String> context =
                RedisSerializationContext.<String, String>newSerializationContext(serializer)
                        .key(serializer)
                        .value(serializer)
                        .hashKey(serializer)
                        .hashValue(serializer)
                        .build();

        log.debug("ReactiveRedisTemplate configured with UTF-8 String serialisers");
        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }

    /**
     * Redisson client used exclusively by {@link com.urlshortener.redirect.service.BloomFilterService}
     * to check {@code BF.EXISTS} on the RedisBloom filter.
     *
     * <p>Reads host and port from the same {@code spring.data.redis.*} environment
     * variables so that only one Redis endpoint needs to be configured.
     */
    @Bean
    public RedissonClient redissonClient() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        String redisPort = System.getenv().getOrDefault("REDIS_PORT", "6379");

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                // Conservative pool for bloom-filter-only usage
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4)
                // Fast fail rather than queue indefinitely on the hot path
                .setConnectTimeout(2000)
                .setTimeout(2000);

        log.info("Redisson client configured — redis://{}:{}", redisHost, redisPort);
        return Redisson.create(config);
    }
}
