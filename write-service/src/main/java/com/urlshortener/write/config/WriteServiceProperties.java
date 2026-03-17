package com.urlshortener.write.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed configuration properties for the Write Service.
 *
 * <p>Bound from the {@code write.*} prefix in {@code application.yml}.  All fields
 * carry sensible defaults for local development so the service can start without
 * any environment variables.
 */
@ConfigurationProperties(prefix = "write")
public class WriteServiceProperties {

    /** Base URL of the Key Generation Service. */
    private String kgsBaseUrl = "http://localhost:8081";

    /**
     * Canonical domain of this URL shortener (e.g. {@code sho.rt}).
     * Used to detect circular redirects where the long URL points back at this service.
     */
    private String ownDomain = "localhost";

    @NestedConfigurationProperty
    private Kafka kafka = new Kafka();

    @NestedConfigurationProperty
    private SafeBrowsing safeBrowsing = new SafeBrowsing();

    @NestedConfigurationProperty
    private Cassandra cassandra = new Cassandra();

    // ── Accessors ────────────────────────────────────────────────────────────

    public String getKgsBaseUrl() {
        return kgsBaseUrl;
    }

    public void setKgsBaseUrl(String kgsBaseUrl) {
        this.kgsBaseUrl = kgsBaseUrl;
    }

    public String getOwnDomain() {
        return ownDomain;
    }

    public void setOwnDomain(String ownDomain) {
        this.ownDomain = ownDomain;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public SafeBrowsing getSafeBrowsing() {
        return safeBrowsing;
    }

    public void setSafeBrowsing(SafeBrowsing safeBrowsing) {
        this.safeBrowsing = safeBrowsing;
    }

    public Cassandra getCassandra() {
        return cassandra;
    }

    public void setCassandra(Cassandra cassandra) {
        this.cassandra = cassandra;
    }

    // ── Nested types ─────────────────────────────────────────────────────────

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
            private String urlCreated = "url.created";
            private String bloomFilter = "bloom.filter.populate";

            public String getUrlCreated() {
                return urlCreated;
            }

            public void setUrlCreated(String urlCreated) {
                this.urlCreated = urlCreated;
            }

            public String getBloomFilter() {
                return bloomFilter;
            }

            public void setBloomFilter(String bloomFilter) {
                this.bloomFilter = bloomFilter;
            }
        }
    }

    public static class SafeBrowsing {
        /**
         * When {@code false} the Safe Browsing check is entirely skipped.
         * When {@code true} URLs are checked against the in-memory {@link #blocklist}.
         */
        private boolean enabled = false;

        /**
         * Configurable blocklist for local development / integration tests.
         * In production this would be replaced by a call to the Google Safe Browsing API.
         */
        private List<String> blocklist = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getBlocklist() {
            return blocklist;
        }

        public void setBlocklist(List<String> blocklist) {
            this.blocklist = blocklist;
        }
    }

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
}
