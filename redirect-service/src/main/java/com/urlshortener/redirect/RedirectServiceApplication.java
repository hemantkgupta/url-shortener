package com.urlshortener.redirect;

import com.urlshortener.redirect.config.RedirectServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Redirect Service — the hot path of the URL shortener.
 *
 * <p>Handles {@code GET /{shortKey}} with a target p99 latency of &lt;50 ms by layering
 * three fast-rejection mechanisms before ever touching ScyllaDB:
 * <ol>
 *   <li>Bloom filter (RedisBloom) — probabilistically rejects keys that were never created</li>
 *   <li>L2 cache (Redis / Lettuce) — serves 99%+ of redirects from memory in &lt;1 ms</li>
 *   <li>XFetch early-refresh — proactively refreshes cache entries approaching expiry to
 *       prevent a thundering-herd on cache expiration</li>
 * </ol>
 *
 * <p>Click events are published fire-and-forget to Kafka on a virtual thread so they
 * never add to the redirect latency.  Geo-enrichment and device attribution happen
 * downstream in the Flink analytics pipeline.
 *
 * <p>Virtual threads (Project Loom) are enabled via {@code spring.threads.virtual.enabled=true}
 * in {@code application.yml}, which causes Spring MVC to dispatch requests on virtual
 * threads automatically (Spring Boot 3.2+), enabling massive concurrency with minimal
 * heap overhead.
 */
@SpringBootApplication
@EnableConfigurationProperties(RedirectServiceProperties.class)
public class RedirectServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(RedirectServiceApplication.class);

    public static void main(String[] args) {
        var context = SpringApplication.run(RedirectServiceApplication.class, args);
        var props = context.getBean(RedirectServiceProperties.class);
        log.info(
                "Redirect Service started — ownDomain={}, bloomFilterKey={}, cacheTtlSeconds={}, "
                        + "kafkaTopic={}",
                props.getOwnDomain(),
                props.getRedis().getBloomFilterKey(),
                props.getRedis().getCacheTtlSeconds(),
                props.getKafka().getTopic().getClickEvents());
    }
}
