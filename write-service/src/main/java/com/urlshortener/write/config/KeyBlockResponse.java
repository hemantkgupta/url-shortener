package com.urlshortener.write.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body returned by the KGS {@code POST /internal/keys/next-block} endpoint.
 *
 * <p>The block defines a raw counter range {@code [startKey, endKey)}.
 * The write-service pod consumes counters from this range atomically via an
 * in-memory counter and encodes them locally, only calling KGS again when the
 * block is exhausted.
 */
public record KeyBlockResponse(
        long startKey,
        long endKey,
        long blockSize,
        String region
) {
    @JsonCreator
    public KeyBlockResponse(
            @JsonProperty("startKey")  long startKey,
            @JsonProperty("endKey")    long endKey,
            @JsonProperty("blockSize") long blockSize,
            @JsonProperty("region")    String region) {
        this.startKey  = startKey;
        this.endKey    = endKey;
        this.blockSize = blockSize;
        this.region    = region;
    }
}
