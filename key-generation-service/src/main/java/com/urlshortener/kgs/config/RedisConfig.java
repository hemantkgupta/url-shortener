package com.urlshortener.kgs.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the Redisson client used to interact with
 * RedisBloom (BF.ADD) for allocated-key tracking.
 *
 * <p>Redisson is chosen over plain Lettuce here because its
 * {@link org.redisson.api.RBloomFilter} abstraction handles the RedisBloom
 * module commands and pipeline batching transparently.
 *
 * <p>The bean is destroyed by Spring calling {@link RedissonClient#shutdown()}.
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    private final KgsProperties kgsProperties;

    public RedisConfig(KgsProperties kgsProperties) {
        this.kgsProperties = kgsProperties;
    }

    /**
     * Creates a {@link RedissonClient} connected to the address specified in
     * {@code kgs.redis.address} (e.g. {@code redis://localhost:6379}).
     *
     * <p>Single-server mode is used here; in production, swap to
     * {@link org.redisson.config.ClusterServersConfig} or
     * {@link org.redisson.config.SentinelServersConfig} by adding a profile
     * override.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        String address = kgsProperties.getRedis().getAddress();
        log.info("Connecting Redisson to Redis at {}", address);

        Config config = new Config();
        config.useSingleServer()
                .setAddress(address)
                // Keep connections alive; KGS makes bursts of BF.ADD calls
                .setConnectionPoolSize(8)
                .setConnectionMinimumIdleSize(2)
                // Fail fast: if Redis is down don't block allocateBlock()
                .setConnectTimeout(2_000)
                .setTimeout(2_000)
                .setRetryAttempts(2)
                .setRetryInterval(500);

        return Redisson.create(config);
    }
}
