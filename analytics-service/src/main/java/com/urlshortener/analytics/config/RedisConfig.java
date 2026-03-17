package com.urlshortener.analytics.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis configuration for the Analytics Service.
 *
 * <p>A {@link RedissonClient} is created for real-time click counter reads
 * via {@code RAtomicLong} (e.g. {@code click_count:{shortKey}}).
 * The Flink sinks use a lightweight {@link redis.clients.jedis.Jedis} connection
 * instead of Redisson to avoid classpath conflicts in the Flink task-manager
 * classloader.
 *
 * <p>In production, swap {@code useSingleServer()} for
 * {@code useClusterServers()} or {@code useSentinelServers()}.
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    private final AnalyticsProperties properties;

    public RedisConfig(AnalyticsProperties properties) {
        this.properties = properties;
    }

    /**
     * Redisson client wired from {@code analytics.redis.*} properties.
     *
     * <p>The bean is destroyed gracefully on application shutdown via
     * {@code destroyMethod = "shutdown"}.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        AnalyticsProperties.Redis redis = properties.getRedis();

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redis.getHost() + ":" + redis.getPort())
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10)
                .setConnectTimeout(3_000)
                .setTimeout(3_000)
                .setRetryAttempts(3)
                .setRetryInterval(1_000);

        log.info("Creating RedissonClient for Analytics — address=redis://{}:{}",
                redis.getHost(), redis.getPort());
        return Redisson.create(config);
    }
}
