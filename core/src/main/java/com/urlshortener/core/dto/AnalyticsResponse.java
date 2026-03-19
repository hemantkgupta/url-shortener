package com.urlshortener.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Analytics summary for a single short key over a requested time window.
 *
 * <p>All {@code clicksBy*} maps are defensive copies and are never {@code null}
 * — consumers can iterate them safely without null checks.
 *
 * <p>Map keys follow these conventions:
 * <ul>
 *   <li>{@code clicksByDay}     — ISO-8601 date string, e.g. {@code "2025-06-01"}</li>
 *   <li>{@code clicksByCountry} — ISO 3166-1 alpha-2 country code, e.g. {@code "US"}</li>
 *   <li>{@code clicksByDevice}  — device category, e.g. {@code "mobile"}</li>
 *   <li>{@code clicksByReferrer}— referrer domain, e.g. {@code "twitter.com"}</li>
 * </ul>
 */
public final class AnalyticsResponse {

    private final String shortKey;
    private final long totalClicks;
    private final Map<String, Long> clicksByDay;
    private final Map<String, Long> clicksByCountry;
    private final Map<String, Long> clicksByDevice;
    private final Map<String, Long> clicksByReferrer;

    private final Instant periodFrom;

    private final Instant periodTo;

    @JsonCreator
    public AnalyticsResponse(
            @JsonProperty("shortKey")        String shortKey,
            @JsonProperty("totalClicks")     long totalClicks,
            @JsonProperty("clicksByDay")     Map<String, Long> clicksByDay,
            @JsonProperty("clicksByCountry") Map<String, Long> clicksByCountry,
            @JsonProperty("clicksByDevice")  Map<String, Long> clicksByDevice,
            @JsonProperty("clicksByReferrer")Map<String, Long> clicksByReferrer,
            @JsonProperty("periodFrom")      Instant periodFrom,
            @JsonProperty("periodTo")        Instant periodTo) {
        this.shortKey        = Objects.requireNonNull(shortKey,    "shortKey must not be null");
        this.totalClicks     = totalClicks;
        this.clicksByDay     = copyOrEmpty(clicksByDay);
        this.clicksByCountry = copyOrEmpty(clicksByCountry);
        this.clicksByDevice  = copyOrEmpty(clicksByDevice);
        this.clicksByReferrer= copyOrEmpty(clicksByReferrer);
        this.periodFrom      = Objects.requireNonNull(periodFrom,  "periodFrom must not be null");
        this.periodTo        = Objects.requireNonNull(periodTo,    "periodTo must not be null");
    }

    private static Map<String, Long> copyOrEmpty(Map<String, Long> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    // ── Static factory / builder ──────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @JsonProperty("shortKey")
    public String getShortKey() {
        return shortKey;
    }

    @JsonProperty("totalClicks")
    public long getTotalClicks() {
        return totalClicks;
    }

    /** Unmodifiable; keys are ISO-8601 date strings. */
    @JsonProperty("clicksByDay")
    public Map<String, Long> getClicksByDay() {
        return clicksByDay;
    }

    /** Unmodifiable; keys are ISO 3166-1 alpha-2 country codes. */
    @JsonProperty("clicksByCountry")
    public Map<String, Long> getClicksByCountry() {
        return clicksByCountry;
    }

    /** Unmodifiable; keys are device categories. */
    @JsonProperty("clicksByDevice")
    public Map<String, Long> getClicksByDevice() {
        return clicksByDevice;
    }

    /** Unmodifiable; keys are referrer domains. */
    @JsonProperty("clicksByReferrer")
    public Map<String, Long> getClicksByReferrer() {
        return clicksByReferrer;
    }

    @JsonProperty("periodFrom")
    public Instant getPeriodFrom() {
        return periodFrom;
    }

    @JsonProperty("periodTo")
    public Instant getPeriodTo() {
        return periodTo;
    }

    // ── equals / hashCode / toString ─────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AnalyticsResponse other)) return false;
        return totalClicks == other.totalClicks
                && Objects.equals(shortKey,         other.shortKey)
                && Objects.equals(clicksByDay,      other.clicksByDay)
                && Objects.equals(clicksByCountry,  other.clicksByCountry)
                && Objects.equals(clicksByDevice,   other.clicksByDevice)
                && Objects.equals(clicksByReferrer, other.clicksByReferrer)
                && Objects.equals(periodFrom,       other.periodFrom)
                && Objects.equals(periodTo,         other.periodTo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                shortKey, totalClicks,
                clicksByDay, clicksByCountry, clicksByDevice, clicksByReferrer,
                periodFrom, periodTo);
    }

    @Override
    public String toString() {
        return "AnalyticsResponse{"
                + "shortKey='" + shortKey + '\''
                + ", totalClicks=" + totalClicks
                + ", clicksByDay=" + clicksByDay
                + ", clicksByCountry=" + clicksByCountry
                + ", clicksByDevice=" + clicksByDevice
                + ", clicksByReferrer=" + clicksByReferrer
                + ", periodFrom=" + periodFrom
                + ", periodTo=" + periodTo
                + '}';
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private String shortKey;
        private long totalClicks;
        private Map<String, Long> clicksByDay;
        private Map<String, Long> clicksByCountry;
        private Map<String, Long> clicksByDevice;
        private Map<String, Long> clicksByReferrer;
        private Instant periodFrom;
        private Instant periodTo;

        private Builder() {}

        public Builder shortKey(String shortKey) {
            this.shortKey = shortKey;
            return this;
        }

        public Builder totalClicks(long totalClicks) {
            this.totalClicks = totalClicks;
            return this;
        }

        public Builder clicksByDay(Map<String, Long> clicksByDay) {
            this.clicksByDay = clicksByDay;
            return this;
        }

        public Builder clicksByCountry(Map<String, Long> clicksByCountry) {
            this.clicksByCountry = clicksByCountry;
            return this;
        }

        public Builder clicksByDevice(Map<String, Long> clicksByDevice) {
            this.clicksByDevice = clicksByDevice;
            return this;
        }

        public Builder clicksByReferrer(Map<String, Long> clicksByReferrer) {
            this.clicksByReferrer = clicksByReferrer;
            return this;
        }

        public Builder periodFrom(Instant periodFrom) {
            this.periodFrom = periodFrom;
            return this;
        }

        public Builder periodTo(Instant periodTo) {
            this.periodTo = periodTo;
            return this;
        }

        public AnalyticsResponse build() {
            return new AnalyticsResponse(
                    shortKey, totalClicks,
                    clicksByDay, clicksByCountry, clicksByDevice, clicksByReferrer,
                    periodFrom, periodTo);
        }
    }
}
