package com.urlshortener.write.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for the Write Service.
 *
 * <p>Two clients are created:
 * <ul>
 *   <li>{@link RedissonClient} — used for Bloom Filter operations (BF.ADD via
 *       {@code RBloomFilter}) through the Redisson API.</li>
 *   <li>{@link ReactiveRedisTemplate} — used for non-blocking cache pre-warming
 *       (SET url:{key} {longUrl} EX {ttl}) via the Project Reactor / Lettuce stack.</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    /**
     * Redisson client configured with a single-server address.
     * In production, swap to {@code useClusterServers()} or {@code useSentinelServers()}.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10)
                .setConnectTimeout(3_000)
                .setTimeout(3_000);

        log.info("Creating RedissonClient — address=redis://{}:{}", redisHost, redisPort);
        return Redisson.create(config);
    }

    /**
     * Reactive Redis template with String key/value serializers.
     * Backed by Lettuce (the default Spring Data Redis driver).
     */
    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {

        StringRedisSerializer serializer = new StringRedisSerializer();
        RedisSerializationContext<String, String> context =
                RedisSerializationContext.<String, String>newSerializationContext(serializer)
                        .key(serializer)
                        .value(serializer)
                        .hashKey(serializer)
                        .hashValue(serializer)
                        .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }
}
