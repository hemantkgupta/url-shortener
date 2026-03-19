package com.urlshortener.kgs.controller;

/**
 * HTTP response body returned by {@code POST /internal/keys/next-block}.
 *
 * <p>Write-service pods use {@code startKey} and {@code endKey} to maintain
 * their own local raw counter range and encode short keys without further KGS
 * round-trips until the block is exhausted.
 *
 * @param startKey  first raw counter in the block (inclusive)
 * @param endKey    first raw counter beyond this block (exclusive)
 * @param blockSize number of keys in the block ({@code end - start})
 * @param region    region that owns this block
 */
public record KeyBlockResponse(
        long startKey,
        long endKey,
        long blockSize,
        String region) {
}
