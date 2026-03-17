package com.urlshortener.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Core domain object representing a short-to-long URL mapping.
 *
 * <p>Immutable by design — use {@link Builder} to construct instances.
 * {@code userId} and {@code expiresAt} are intentionally nullable to support
 * anonymous mappings and non-expiring links respectively.
 */
public final class UrlMapping {

    private final String shortKey;
    private final String longUrl;
    private final Long userId;          // nullable — anonymous if absent
    private final Instant createdAt;
    private final Instant expiresAt;    // nullable — no expiry if absent
    private final boolean isActive;

    private UrlMapping(Builder builder) {
        this.shortKey  = Objects.requireNonNull(builder.shortKey,  "shortKey must not be null");
        this.longUrl   = Objects.requireNonNull(builder.longUrl,   "longUrl must not be null");
        this.userId    = builder.userId;
        this.createdAt = Objects.requireNonNull(builder.createdAt, "createdAt must not be null");
        this.expiresAt = builder.expiresAt;
        this.isActive  = builder.isActive;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public String getShortKey() {
        return shortKey;
    }

    public String getLongUrl() {
        return longUrl;
    }

    /** May be {@code null} for anonymous (unauthenticated) mappings. */
    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** May be {@code null} when the link never expires. */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isActive() {
        return isActive;
    }

    // ── Derived helpers ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the mapping has a configured expiry time
     * that is strictly before the given instant.
     *
     * @param now the reference instant (typically {@link Instant#now()})
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && expiresAt.isBefore(Objects.requireNonNull(now, "now must not be null"));
    }

    /**
     * Returns a new {@link Builder} pre-populated with all fields of this
     * instance — useful for creating modified copies.
     */
    public Builder toBuilder() {
        return new Builder()
                .shortKey(shortKey)
                .longUrl(longUrl)
                .userId(userId)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .isActive(isActive);
    }

    // ── equals / hashCode / toString ─────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UrlMapping other)) return false;
        return isActive == other.isActive
                && Objects.equals(shortKey,  other.shortKey)
                && Objects.equals(longUrl,   other.longUrl)
                && Objects.equals(userId,    other.userId)
                && Objects.equals(createdAt, other.createdAt)
                && Objects.equals(expiresAt, other.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shortKey, longUrl, userId, createdAt, expiresAt, isActive);
    }

    @Override
    public String toString() {
        return "UrlMapping{"
                + "shortKey='" + shortKey + '\''
                + ", longUrl='" + longUrl + '\''
                + ", userId=" + userId
                + ", createdAt=" + createdAt
                + ", expiresAt=" + expiresAt
                + ", isActive=" + isActive
                + '}';
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String shortKey;
        private String longUrl;
        private Long userId;
        private Instant createdAt;
        private Instant expiresAt;
        private boolean isActive = true;   // sensible default

        private Builder() {}

        public Builder shortKey(String shortKey) {
            this.shortKey = shortKey;
            return this;
        }

        public Builder longUrl(String longUrl) {
            this.longUrl = longUrl;
            return this;
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder isActive(boolean isActive) {
            this.isActive = isActive;
            return this;
        }

        public UrlMapping build() {
            return new UrlMapping(this);
        }
    }
}
