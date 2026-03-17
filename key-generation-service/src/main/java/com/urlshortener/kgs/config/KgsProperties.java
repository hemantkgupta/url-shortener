package com.urlshortener.kgs.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Strongly-typed configuration properties for the Key Generation Service.
 *
 * <p>All properties are bound from the {@code kgs.*} namespace in
 * {@code application.yml}.  Spring Boot validates the bean at startup using
 * the JSR-380 constraints declared on each field.
 */
@ConfigurationProperties(prefix = "kgs")
@Validated
public class KgsProperties {

    @Valid
    @NotNull
    private EtcdProperties etcd = new EtcdProperties();

    @Valid
    @NotNull
    private RedisProperties redis = new RedisProperties();

    /** Number of keys per allocated block.  Minimum 1; typical value 10 000. */
    @Min(1)
    private long blockSize = 10_000L;

    /**
     * Logical region identifier for this pod.
     * Drives the counter start offset to ensure non-overlapping global key
     * spaces across regions (see {@link #regionOffsets}).
     */
    @NotBlank
    private String region = "local";

    /**
     * Per-region starting offsets.  Each region must be assigned a distinct,
     * non-overlapping range large enough to accommodate the expected key volume.
     *
     * <p>Default layout:
     * <ul>
     *   <li>{@code local}        → 0
     *   <li>{@code us-east}      → 1 000 000 000 000
     *   <li>{@code eu-west}      → 2 000 000 000 000
     *   <li>{@code ap-northeast} → 3 000 000 000 000
     * </ul>
     */
    @NotNull
    private Map<String, Long> regionOffsets = defaultRegionOffsets();

    // ── Nested: etcd ─────────────────────────────────────────────────────────

    public static class EtcdProperties {

        @NotBlank
        private String endpoints = "http://localhost:2379";

        public String getEndpoints() {
            return endpoints;
        }

        public void setEndpoints(String endpoints) {
            this.endpoints = endpoints;
        }
    }

    // ── Nested: Redis ─────────────────────────────────────────────────────────

    public static class RedisProperties {

        @NotBlank
        private String address = "redis://localhost:6379";

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public EtcdProperties getEtcd() {
        return etcd;
    }

    public void setEtcd(EtcdProperties etcd) {
        this.etcd = etcd;
    }

    public RedisProperties getRedis() {
        return redis;
    }

    public void setRedis(RedisProperties redis) {
        this.redis = redis;
    }

    public long getBlockSize() {
        return blockSize;
    }

    public void setBlockSize(long blockSize) {
        this.blockSize = blockSize;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, Long> getRegionOffsets() {
        return regionOffsets;
    }

    public void setRegionOffsets(Map<String, Long> regionOffsets) {
        this.regionOffsets = regionOffsets;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the start offset for the configured {@link #region}.
     * Falls back to 0 when the region is not found in {@link #regionOffsets}.
     */
    public long regionStartOffset() {
        return regionOffsets.getOrDefault(region, 0L);
    }

    private static Map<String, Long> defaultRegionOffsets() {
        Map<String, Long> m = new HashMap<>();
        m.put("local",        0L);
        m.put("us-east",      1_000_000_000_000L);
        m.put("eu-west",      2_000_000_000_000L);
        m.put("ap-northeast", 3_000_000_000_000L);
        return m;
    }
}
