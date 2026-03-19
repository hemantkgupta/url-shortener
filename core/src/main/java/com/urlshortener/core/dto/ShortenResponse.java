package com.urlshortener.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * Response payload returned after successfully shortening a URL.
 *
 * <p>Both {@link Instant} fields are serialized as ISO-8601 strings
 * (e.g. {@code "2025-06-01T12:00:00Z"}) using the Jackson JSR-310 module.
 * Callers must register {@code com.fasterxml.jackson.datatype.jsr310.JavaTimeModule}
 * — the {@code core} module's {@code ObjectMapper} bean does this automatically.
 */
public final class ShortenResponse {

    private final String shortUrl;
    private final String shortKey;
    private final String longUrl;

    private final Instant expiresAt;   // nullable — absent when link never expires

    private final Instant createdAt;

    @JsonCreator
    public ShortenResponse(
            @JsonProperty("shortUrl")  String shortUrl,
            @JsonProperty("shortKey")  String shortKey,
            @JsonProperty("longUrl")   String longUrl,
            @JsonProperty("expiresAt") Instant expiresAt,
            @JsonProperty("createdAt") Instant createdAt) {
        this.shortUrl  = Objects.requireNonNull(shortUrl,  "shortUrl must not be null");
        this.shortKey  = Objects.requireNonNull(shortKey,  "shortKey must not be null");
        this.longUrl   = Objects.requireNonNull(longUrl,   "longUrl must not be null");
        this.expiresAt = expiresAt;   // nullable
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    // ── Static factory ────────────────────────────────────────────────────────

    public static ShortenResponse of(
            String shortUrl,
            String shortKey,
            String longUrl,
            Instant expiresAt,
            Instant createdAt) {
        return new ShortenResponse(shortUrl, shortKey, longUrl, expiresAt, createdAt);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @JsonProperty("shortUrl")
    public String getShortUrl() {
        return shortUrl;
    }

    @JsonProperty("shortKey")
    public String getShortKey() {
        return shortKey;
    }

    @JsonProperty("longUrl")
    public String getLongUrl() {
        return longUrl;
    }

    /** ISO-8601; {@code null} when the link never expires. */
    @JsonProperty("expiresAt")
    public Instant getExpiresAt() {
        return expiresAt;
    }

    @JsonProperty("createdAt")
    public Instant getCreatedAt() {
        return createdAt;
    }

    // ── equals / hashCode / toString ─────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShortenResponse other)) return false;
        return Objects.equals(shortUrl,  other.shortUrl)
                && Objects.equals(shortKey,  other.shortKey)
                && Objects.equals(longUrl,   other.longUrl)
                && Objects.equals(expiresAt, other.expiresAt)
                && Objects.equals(createdAt, other.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shortUrl, shortKey, longUrl, expiresAt, createdAt);
    }

    @Override
    public String toString() {
        return "ShortenResponse{"
                + "shortUrl='" + shortUrl + '\''
                + ", shortKey='" + shortKey + '\''
                + ", longUrl='" + longUrl + '\''
                + ", expiresAt=" + expiresAt
                + ", createdAt=" + createdAt
                + '}';
    }
}
