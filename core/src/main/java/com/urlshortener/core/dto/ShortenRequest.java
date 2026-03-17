package com.urlshortener.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for the URL-shortening endpoint.
 *
 * <p>All validation is expressed via Jakarta Validation annotations so that
 * any compliant validator (e.g. Hibernate Validator) can enforce constraints
 * without coupling to a specific framework.
 *
 * <p>Jackson deserializes this class from JSON; the {@code @JsonCreator}
 * constructor makes it work cleanly with both standard and strict mapper
 * configurations.
 */
public final class ShortenRequest {

    /**
     * The original long URL to shorten.
     *
     * <p>Must be a syntactically valid absolute URL beginning with
     * {@code http://} or {@code https://}, and no longer than 2 048
     * characters (matches {@link com.urlshortener.core.util.UrlValidator}).
     */
    @NotBlank(message = "longUrl must not be blank")
    @Size(max = 2048, message = "longUrl must not exceed 2048 characters")
    @Pattern(
        regexp = "^https?://[^\\s/$.?#].[^\\s]*$",
        message = "longUrl must be a valid absolute HTTP or HTTPS URL"
    )
    private final String longUrl;

    /**
     * Optional vanity / custom short key chosen by the caller.
     *
     * <p>When present: 1–32 characters, alphanumeric and hyphens only
     * (no leading/trailing hyphens, but that business rule is enforced at
     * the service layer).  When absent ({@code null}) the system generates
     * a Base62-encoded key automatically.
     */
    @Size(max = 32, message = "customKey must not exceed 32 characters")
    @Pattern(
        regexp = "^[A-Za-z0-9-]*$",
        message = "customKey may only contain alphanumeric characters and hyphens"
    )
    private final String customKey;   // nullable

    /**
     * Optional time-to-live in days.  {@code null} means the link never
     * expires; values must be in the range [1, 3650] (≈ 10 years).
     */
    @Min(value = 1,    message = "ttlDays must be at least 1")
    @Max(value = 3650, message = "ttlDays must not exceed 3650")
    private final Integer ttlDays;    // nullable

    @JsonCreator
    public ShortenRequest(
            @JsonProperty("longUrl")   String longUrl,
            @JsonProperty("customKey") String customKey,
            @JsonProperty("ttlDays")   Integer ttlDays) {
        this.longUrl   = longUrl;
        this.customKey = customKey;
        this.ttlDays   = ttlDays;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @JsonProperty("longUrl")
    public String getLongUrl() {
        return longUrl;
    }

    @JsonProperty("customKey")
    public String getCustomKey() {
        return customKey;
    }

    @JsonProperty("ttlDays")
    public Integer getTtlDays() {
        return ttlDays;
    }

    // ── equals / hashCode / toString ─────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShortenRequest other)) return false;
        return java.util.Objects.equals(longUrl,   other.longUrl)
                && java.util.Objects.equals(customKey, other.customKey)
                && java.util.Objects.equals(ttlDays,   other.ttlDays);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(longUrl, customKey, ttlDays);
    }

    @Override
    public String toString() {
        return "ShortenRequest{"
                + "longUrl='" + longUrl + '\''
                + ", customKey='" + customKey + '\''
                + ", ttlDays=" + ttlDays
                + '}';
    }
}
