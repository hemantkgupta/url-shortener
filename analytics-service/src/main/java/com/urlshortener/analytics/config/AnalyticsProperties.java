package com.urlshortener.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Typed configuration properties for the Analytics Service.
 *
 * <p>Bound from the {@code analytics.*} prefix in {@code application.yml}.
 * All nested classes carry sensible defaults for local development so the service
 * can start without any environment variables.
 */
@ConfigurationProperties(prefix = "analytics")
public class AnalyticsProperties {

    @NestedConfigurationProperty
    private Kafka kafka = new Kafka();

    @NestedConfigurationProperty
    private Flink flink = new Flink();

    @NestedConfigurationProperty
    private ClickHouse clickhouse = new ClickHouse();

    @NestedConfigurationProperty
    private Redis redis = new Redis();

    @NestedConfigurationProperty
    private Cassandra cassandra = new Cassandra();

    @NestedConfigurationProperty
    private Security security = new Security();

    // ── Accessors ────────────────────────────────────────────────────────────

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Flink getFlink() {
        return flink;
    }

    public void setFlink(Flink flink) {
        this.flink = flink;
    }

    public ClickHouse getClickhouse() {
        return clickhouse;
    }

    public void setClickhouse(ClickHouse clickhouse) {
        this.clickhouse = clickhouse;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Cassandra getCassandra() {
        return cassandra;
    }

    public void setCassandra(Cassandra cassandra) {
        this.cassandra = cassandra;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    // ── Nested: Kafka ─────────────────────────────────────────────────────────

    public static class Kafka {

        private String bootstrapServers = "localhost:9092";
        private String groupId = "analytics-flink";

        @NestedConfigurationProperty
        private Topic topic = new Topic();

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

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

    // ── Nested: Flink ─────────────────────────────────────────────────────────

    public static class Flink {

        /**
         * When {@code true}, a Flink mini-cluster is started in-process for
         * local development and CI.  Set to {@code false} in production where
         * the Flink job is submitted to a dedicated cluster.
         */
        private boolean embedded = true;

        /** Checkpoint interval in milliseconds. */
        private long checkpointIntervalMs = 10_000L;

        /** Tumbling time-window size used for micro-batch aggregation (seconds). */
        private long windowSizeSeconds = 10L;

        public boolean isEmbedded() {
            return embedded;
        }

        public void setEmbedded(boolean embedded) {
            this.embedded = embedded;
        }

        public long getCheckpointIntervalMs() {
            return checkpointIntervalMs;
        }

        public void setCheckpointIntervalMs(long checkpointIntervalMs) {
            this.checkpointIntervalMs = checkpointIntervalMs;
        }

        public long getWindowSizeSeconds() {
            return windowSizeSeconds;
        }

        public void setWindowSizeSeconds(long windowSizeSeconds) {
            this.windowSizeSeconds = windowSizeSeconds;
        }
    }

    // ── Nested: ClickHouse ────────────────────────────────────────────────────

    public static class ClickHouse {

        private String url = "jdbc:clickhouse://localhost:8123/analytics";
        private String username = "default";
        private String password = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    // ── Nested: Redis ─────────────────────────────────────────────────────────

    public static class Redis {

        private String host = "localhost";
        private int port = 6379;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    // ── Nested: Cassandra ─────────────────────────────────────────────────────

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

    public static class Security {

        @NestedConfigurationProperty
        private Google google = new Google();

        public Google getGoogle() {
            return google;
        }

        public void setGoogle(Google google) {
            this.google = google;
        }

        public static class Google {
            private String issuerUri = "https://accounts.google.com";
            private String jwkSetUri = "https://www.googleapis.com/oauth2/v3/certs";
            private String audience = "";

            public String getIssuerUri() {
                return issuerUri;
            }

            public void setIssuerUri(String issuerUri) {
                this.issuerUri = issuerUri;
            }

            public String getJwkSetUri() {
                return jwkSetUri;
            }

            public void setJwkSetUri(String jwkSetUri) {
                this.jwkSetUri = jwkSetUri;
            }

            public String getAudience() {
                return audience;
            }

            public void setAudience(String audience) {
                this.audience = audience;
            }
        }
    }
}
