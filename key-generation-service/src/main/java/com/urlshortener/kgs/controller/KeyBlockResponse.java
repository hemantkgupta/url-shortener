package com.urlshortener.kgs.controller;

/**
 * HTTP response body returned by {@code POST /internal/keys/next-block}.
 *
 * <p>Write-service pods use {@code startKey} and {@code endKey} to maintain
 * their own local counters and encode short keys without further KGS
 * round-trips until the block is exhausted.
 *
 * @param startKey  Base-62 encoded first key in the block
 * @param endKey    Base-62 encoded first key beyond this block (exclusive)
 * @param blockSize number of keys in the block ({@code end - start} in raw counter space)
 * @param region    region that owns this block
 */
public record KeyBlockResponse(
        String startKey,
        String endKey,
        long blockSize,
        String region) {
}
