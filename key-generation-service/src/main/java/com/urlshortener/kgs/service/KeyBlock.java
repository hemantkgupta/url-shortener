package com.urlshortener.kgs.service;

/**
 * An immutable value object representing a contiguous range of numeric counter
 * values that have been exclusively reserved for this KGS pod.
 *
 * <p>{@code start} is inclusive; {@code end} is exclusive, so valid counter
 * values are {@code [start, end)}.
 *
 * @param start  first counter value in the block (inclusive)
 * @param end    first counter value beyond this block (exclusive)
 * @param region logical region that owns this block (e.g. "us-east")
 */
public record KeyBlock(long start, long end, String region) {

    /**
     * Compact constructor validates that {@code start < end}.
     */
    public KeyBlock {
        if (start >= end) {
            throw new IllegalArgumentException(
                    "KeyBlock requires start < end, got start=" + start + " end=" + end);
        }
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("KeyBlock region must not be blank");
        }
    }

    /** Number of keys in this block. */
    public long size() {
        return end - start;
    }

    @Override
    public String toString() {
        return "KeyBlock{start=" + start + ", end=" + end + ", region='" + region + "', size=" + size() + "}";
    }
}
