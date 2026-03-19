package com.urlshortener.write.config;

import com.urlshortener.core.util.Base62Encoder;
import com.urlshortener.write.exception.KeyGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HTTP client for the Key Generation Service (KGS).
 *
 * <p>Each write-service pod maintains an in-memory block of pre-allocated counter
 * values obtained from KGS.  A key is consumed by incrementing an {@link AtomicLong};
 * when the block is exhausted a new block is fetched under a {@link ReentrantLock}
 * (double-checked locking) to avoid a thundering herd of concurrent KGS calls.
 *
 * <p>The raw counter value is mapped into the public 8-character Base62 range
 * before being returned to callers, producing compact public keys that do not
 * expose the monotonic counter directly.
 */
@Component
public class KgsClient {

    private static final Logger log = LoggerFactory.getLogger(KgsClient.class);

    private static final String NEXT_BLOCK_PATH = "/internal/keys/next-block";

    private final WriteServiceProperties properties;
    private final RestClient restClient;

    // Mutable block state — protected by blockLock
    private final AtomicLong counter  = new AtomicLong(0);
    private volatile long    blockEnd = 0L;    // exclusive upper bound

    private final ReentrantLock blockLock = new ReentrantLock();

    public KgsClient(WriteServiceProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getKgsBaseUrl())
                .build();
    }

    /**
     * Returns the next available short key as a scattered Base62 string.
     *
     * <p>This method is thread-safe.  Concurrent callers may briefly contend on
     * {@link #blockLock} when a block refresh is needed, but only one thread will
     * actually call KGS; the rest wait and then consume from the refreshed block.
     *
     * @throws KeyGenerationException if KGS is unavailable or returns an error
     */
    public String nextKey() {
        long value = counter.getAndIncrement();
        if (value >= blockEnd) {
            value = refreshBlockAndGetFirst();
        }
        return Base62Encoder.toShortKey(value);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private long refreshBlockAndGetFirst() {
        blockLock.lock();
        try {
            // Double-check: another thread may have already refreshed while we waited
            long current = counter.getAndIncrement();
            if (current < blockEnd) {
                return current;
            }

            log.info("Key block exhausted — fetching new block from KGS at {}", properties.getKgsBaseUrl());
            KeyBlockResponse block = fetchBlock();
            log.info("New block received — startKey={}, endKey={}, region={}",
                    block.startKey(), block.endKey(), block.region());

            blockEnd = block.endKey();
            counter.set(block.startKey() + 1);   // first key is returned immediately
            return block.startKey();
        } finally {
            blockLock.unlock();
        }
    }

    private KeyBlockResponse fetchBlock() {
        try {
            KeyBlockResponse response = restClient.post()
                    .uri(NEXT_BLOCK_PATH)
                    .retrieve()
                    .body(KeyBlockResponse.class);

            if (response == null) {
                throw new KeyGenerationException("KGS returned null response for next-block");
            }
            return response;
        } catch (RestClientException ex) {
            throw new KeyGenerationException("Failed to fetch key block from KGS: " + ex.getMessage(), ex);
        }
    }
}
