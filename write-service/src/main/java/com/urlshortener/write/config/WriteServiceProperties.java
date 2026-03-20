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

    /**
     * Explicit public scheme override for generated short URLs.
     *
     * <p>When blank, the service falls back to the existing localhost-aware logic:
     * localhost/127.0.0.1/0.0.0.0 => http, everything else => https.
     */
    private String publicScheme = "";

    @NestedConfigurationProperty
    private Kafka kafka = new Kafka();

    @NestedConfigurationProperty
    private SafeBrowsing safeBrowsing = new SafeBrowsing();

    @NestedConfigurationProperty
    private Cassandra cassandra = new Cassandra();

    @NestedConfigurationProperty
    private Cdn cdn = new Cdn();

    @NestedConfigurationProperty
    private Security security = new Security();

    @NestedConfigurationProperty
    private RateLimit rateLimit = new RateLimit();

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

    public String getPublicScheme() {
        return publicScheme;
    }

    public void setPublicScheme(String publicScheme) {
        this.publicScheme = publicScheme;
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

    public Cdn getCdn() {
        return cdn;
    }

    public void setCdn(Cdn cdn) {
        this.cdn = cdn;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    // ── Nested types ─────────────────────────────────────────────────────────

    /**
     * CDN cache invalidation configuration.
     *
     * <p>When {@code enabled=true} the {@code CloudflareCdnPurgeService} is activated and
     * will call the Cloudflare Cache-Tag API to immediately purge deleted short URLs from
     * all CDN edge nodes.  When {@code false} (default) the no-op implementation is used,
     * which only logs purge events — safe for local development with no outbound calls.
     */
    public static class Cdn {

        /**
         * Enables the real Cloudflare CDN purge implementation.
         * Default: {@code false} — uses {@code NoOpCdnPurgeService} for local dev.
         */
        private boolean enabled = false;

        @NestedConfigurationProperty
        private Cloudflare cloudflare = new Cloudflare();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Cloudflare getCloudflare() {
            return cloudflare;
        }

        public void setCloudflare(Cloudflare cloudflare) {
            this.cloudflare = cloudflare;
        }

        public static class Cloudflare {

            /** Cloudflare Zone ID — found in the dashboard under your domain overview. */
            private String zoneId = "";

            /**
             * Cloudflare API token with {@code Cache Purge} permission.
             * Never commit a real value — inject via environment variable.
             */
            private String apiToken = "";

            public String getZoneId() {
                return zoneId;
            }

            public void setZoneId(String zoneId) {
                this.zoneId = zoneId;
            }

            public String getApiToken() {
                return apiToken;
            }

            public void setApiToken(String apiToken) {
                this.apiToken = apiToken;
            }
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

    public static class RateLimit {
        private int anonymousCreatesPerHour = 30;
        private int authenticatedCreatesPerHour = 300;

        public int getAnonymousCreatesPerHour() {
            return anonymousCreatesPerHour;
        }

        public void setAnonymousCreatesPerHour(int anonymousCreatesPerHour) {
            this.anonymousCreatesPerHour = anonymousCreatesPerHour;
        }

        public int getAuthenticatedCreatesPerHour() {
            return authenticatedCreatesPerHour;
        }

        public void setAuthenticatedCreatesPerHour(int authenticatedCreatesPerHour) {
            this.authenticatedCreatesPerHour = authenticatedCreatesPerHour;
        }
    }
}
