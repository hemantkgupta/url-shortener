package com.urlshortener.redirect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Typed configuration properties for the Redirect Service.
 *
 * <p>Bound from the {@code redirect.*} prefix in {@code application.yml}.  All fields
 * carry sensible defaults for local development so the service can start without
 * any environment variables.
 */
@ConfigurationProperties(prefix = "redirect")
public class RedirectServiceProperties {

    /**
     * Canonical domain of this URL shortener (e.g. {@code sho.rt}).
     * Used to detect circular redirects.
     */
    private String ownDomain = "localhost";

    /**
     * Daily salt used to hash IP addresses for GDPR-safe analytics.
     * Rotate via an environment variable; never hard-code in production.
     */
    private String ipDailySalt = "dev-salt-change-in-prod";

    @NestedConfigurationProperty
    private Cassandra cassandra = new Cassandra();

    @NestedConfigurationProperty
    private Redis redis = new Redis();

    @NestedConfigurationProperty
    private Kafka kafka = new Kafka();

    @NestedConfigurationProperty
    private Cdn cdn = new Cdn();

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getOwnDomain() {
        return ownDomain;
    }

    public void setOwnDomain(String ownDomain) {
        this.ownDomain = ownDomain;
    }

    public String getIpDailySalt() {
        return ipDailySalt;
    }

    public void setIpDailySalt(String ipDailySalt) {
        this.ipDailySalt = ipDailySalt;
    }

    public Cassandra getCassandra() {
        return cassandra;
    }

    public void setCassandra(Cassandra cassandra) {
        this.cassandra = cassandra;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Cdn getCdn() {
        return cdn;
    }

    public void setCdn(Cdn cdn) {
        this.cdn = cdn;
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    public static class Cassandra {
        private String contactPoints = "localhost";
        private int port = 9042;
        private String keyspace = "url_shortener";
        private String datacenter = "datacenter1";

        public String getContactPoints() {
            return contactPoints;
        }

        public void setContactPoints(String contactPoints) {
            this.contactPoints = contactPoints;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getKeyspace() {
            return keyspace;
        }

        public void setKeyspace(String keyspace) {
            this.keyspace = keyspace;
        }

        public String getDatacenter() {
            return datacenter;
        }

        public void setDatacenter(String datacenter) {
            this.datacenter = datacenter;
        }
    }

    public static class Redis {
        /** Name of the RedisBloom filter key. */
        private String bloomFilterKey = "url:bloom";

        /** Prefix prepended to every short-key when caching the long URL. */
        private String urlKeyPrefix = "url:";

        /** TTL in seconds for cached URL mappings (default: 24 hours). */
        private long cacheTtlSeconds = 86400L;

        /**
         * XFetch beta parameter.  Higher values trigger early refresh more aggressively.
         * A value of 1.0 is the standard recommendation.
         */
        private double xfetchBeta = 1.0;

        public String getBloomFilterKey() {
            return bloomFilterKey;
        }

        public void setBloomFilterKey(String bloomFilterKey) {
            this.bloomFilterKey = bloomFilterKey;
        }

        public String getUrlKeyPrefix() {
            return urlKeyPrefix;
        }

        public void setUrlKeyPrefix(String urlKeyPrefix) {
            this.urlKeyPrefix = urlKeyPrefix;
        }

        public long getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public void setCacheTtlSeconds(long cacheTtlSeconds) {
            this.cacheTtlSeconds = cacheTtlSeconds;
        }

        public double getXfetchBeta() {
            return xfetchBeta;
        }

        public void setXfetchBeta(double xfetchBeta) {
            this.xfetchBeta = xfetchBeta;
        }
    }

    /**
     * CDN caching configuration for redirect responses.
     *
     * <p>The CDN is the L1 cache layer (Cloudflare/Fastly, ~300 PoPs) that absorbs ~80%
     * of redirect traffic before requests reach this origin service.  The Bloom filter
     * and Redis cache only run on CDN misses.
     */
    public static class Cdn {

        /**
         * {@code max-age} and {@code s-maxage} value (seconds) set on {@code Cache-Control}
         * for successful 302 responses.  The CDN caches the redirect for this duration.
         *
         * <p>Default: 3 600 s (1 hour) — matches the CDN TTL in the system design.
         * Set to 0 to disable CDN caching entirely (useful for testing).
         */
        private long cacheMaxAgeSeconds = 3_600L;

        public long getCacheMaxAgeSeconds() {
            return cacheMaxAgeSeconds;
        }

        public void setCacheMaxAgeSeconds(long cacheMaxAgeSeconds) {
            this.cacheMaxAgeSeconds = cacheMaxAgeSeconds;
        }
    }

    public static class Kafka {

        @NestedConfigurationProperty
        private Topic topic = new Topic();

        public Topic getTopic() {
            return topic;
        }

        public void setTopic(Topic topic) {
            this.topic = topic;
        }

        public static class Topic {
            private String clickEvents = "click.events";

            public String getClickEvents() {
                return clickEvents;
            }

            public void setClickEvents(String clickEvents) {
                this.clickEvents = clickEvents;
            }
        }
    }
}
