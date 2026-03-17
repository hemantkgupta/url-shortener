package com.urlshortener.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain event capturing a single redirect (click) on a short URL.
 *
 * <p><strong>Privacy / GDPR note:</strong> the raw client IP address is
 * <em>never</em> stored here.  Instead, {@code ipHash} holds the hex-encoded
 * SHA-256 of {@code ip + ":" + dailySalt} (see {@link com.urlshortener.core.util.IpHasher}).
 * Rotating the daily salt makes reversal computationally infeasible while still
 * allowing same-day deduplication by IP.
 *
 * <p>Use {@link Builder} to construct instances.
 */
public final class ClickEvent {

    private final String shortKey;
    private final Instant eventTime;
    private final String country;
    private final String city;
    private final String referrer;
    private final String device;
    private final String browser;
    /**
     * SHA-256 hex of {@code (ip + ":" + dailySalt)}.
     * Never holds a raw IP address.
     */
    private final String ipHash;

    private ClickEvent(Builder builder) {
        this.shortKey  = Objects.requireNonNull(builder.shortKey,  "shortKey must not be null");
        this.eventTime = Objects.requireNonNull(builder.eventTime, "eventTime must not be null");
        this.country   = builder.country;
        this.city      = builder.city;
        this.referrer  = builder.referrer;
        this.device    = builder.device;
        this.browser   = builder.browser;
        this.ipHash    = builder.ipHash;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public String getShortKey() {
        return shortKey;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    /** ISO 3166-1 alpha-2 country code, or {@code null} when geo-lookup failed. */
    public String getCountry() {
        return country;
    }

    /** City name from geo-lookup, or {@code null} when unavailable. */
    public String getCity() {
        return city;
    }

    /** HTTP {@code Referer} header value, or {@code null} when absent. */
    public String getReferrer() {
        return referrer;
    }

    /** Device category, e.g. {@code "mobile"}, {@code "desktop"}, {@code "tablet"}. */
    public String getDevice() {
        return device;
    }

    /** Browser family, e.g. {@code "Chrome"}, {@code "Safari"}. */
    public String getBrowser() {
        return browser;
    }

    /**
     * GDPR-safe SHA-256 hex of the visitor's IP combined with a daily salt.
     * May be {@code null} if hashing was skipped (e.g. internal health-check traffic).
     */
    public String getIpHash() {
        return ipHash;
    }

    // ── Derived helpers ──────────────────────────────────────────────────────

    /**
     * Returns a new {@link Builder} pre-populated with all fields of this
     * instance — useful for creating enriched copies (e.g. after geo-lookup).
     */
    public Builder toBuilder() {
        return new Builder()
                .shortKey(shortKey)
                .eventTime(eventTime)
                .country(country)
                .city(city)
                .referrer(referrer)
                .device(device)
                .browser(browser)
                .ipHash(ipHash);
    }

    // ── equals / hashCode / toString ─────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClickEvent other)) return false;
        return Objects.equals(shortKey,  other.shortKey)
                && Objects.equals(eventTime, other.eventTime)
                && Objects.equals(country,   other.country)
                && Objects.equals(city,      other.city)
                && Objects.equals(referrer,  other.referrer)
                && Objects.equals(device,    other.device)
                && Objects.equals(browser,   other.browser)
                && Objects.equals(ipHash,    other.ipHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shortKey, eventTime, country, city, referrer, device, browser, ipHash);
    }

    @Override
    public String toString() {
        // ipHash is safe to log; no raw IP is ever included.
        return "ClickEvent{"
                + "shortKey='" + shortKey + '\''
                + ", eventTime=" + eventTime
                + ", country='" + country + '\''
                + ", city='" + city + '\''
                + ", referrer='" + referrer + '\''
                + ", device='" + device + '\''
                + ", browser='" + browser + '\''
                + ", ipHash='" + ipHash + '\''
                + '}';
    }

    // ── Static factory ───────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static final class Builder {

        private String shortKey;
        private Instant eventTime;
        private String country;
        private String city;
        private String referrer;
        private String device;
        private String browser;
        private String ipHash;

        private Builder() {}

        public Builder shortKey(String shortKey) {
            this.shortKey = shortKey;
            return this;
        }

        public Builder eventTime(Instant eventTime) {
            this.eventTime = eventTime;
            return this;
        }

        public Builder country(String country) {
            this.country = country;
            return this;
        }

        public Builder city(String city) {
            this.city = city;
            return this;
        }

        public Builder referrer(String referrer) {
            this.referrer = referrer;
            return this;
        }

        public Builder device(String device) {
            this.device = device;
            return this;
        }

        public Builder browser(String browser) {
            this.browser = browser;
            return this;
        }

        /**
         * Sets the GDPR-safe IP hash.
         * Use {@link com.urlshortener.core.util.IpHasher} to produce the value.
         *
         * @param ipHash hex-encoded SHA-256 of {@code ip + ":" + dailySalt}
         */
        public Builder ipHash(String ipHash) {
            this.ipHash = ipHash;
            return this;
        }

        public ClickEvent build() {
            return new ClickEvent(this);
        }
    }
}
