package com.urlshortener.kgs.service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.urlshortener.core.util.Base62Encoder;

/**
 * Per-pod in-memory counter cache that serves short keys without an etcd
 * round-trip on every request.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Holds one "current" {@link KeyBlock} at a time.
 *   <li>{@link #nextKey()} atomically increments an {@link AtomicLong} counter.
 *       When the counter reaches the end of the block a new block is fetched
 *       from {@link BlockAllocator} while holding a non-reentrant lock so that
 *       only one thread triggers the remote allocation.
 *   <li>Before encoding, the raw counter is passed through
 *       {@link Base62Encoder#toShortKey(long)}, which maps it into the public
 *       8-character Base62 range without exposing the monotonic sequence
 *       directly.
 * </ul>
 *
 * <h2>Thread safety</h2>
 * {@link AtomicLong} guarantees that each thread receives a unique counter
 * value.  The block-refresh path is guarded by a {@link ReentrantLock} so only
 * one thread at a time calls into etcd; all others spin-wait (lock) until the
 * new block is installed.
 */
@Service
public class KeyBlockCache {

    private static final Logger log = LoggerFactory.getLogger(KeyBlockCache.class);

    private final BlockAllocator blockAllocator;

    /** Points to the next counter value to hand out. */
    private final AtomicLong counter = new AtomicLong(0L);

    /** Exclusive upper bound of the current block (counter must stay < blockEnd). */
    private volatile long blockEnd = 0L;

    /** Guards the block-refresh critical section. */
    private final ReentrantLock refreshLock = new ReentrantLock();

    /** Snapshot of the current block — exposed via the health endpoint. */
    private volatile KeyBlock currentBlock;

    public KeyBlockCache(BlockAllocator blockAllocator) {
        this.blockAllocator = blockAllocator;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the next unique short key.
     *
     * <p>The raw counter value is converted into the public 8-character Base62
     * range via {@link Base62Encoder#toShortKey(long)}.
     *
     * @return an 8-character Base-62 encoded key string
     */
    public String nextKey() {
        long raw = nextCounter();
        return Base62Encoder.toShortKey(raw);
    }

    /**
     * Returns the {@link KeyBlock} currently loaded in this pod's cache.
     * May be {@code null} if no block has been allocated yet.
     */
    public KeyBlock currentBlock() {
        return currentBlock;
    }

    // ── Internal counter management ───────────────────────────────────────────

    /**
     * Atomically advances the counter and ensures a valid block is active.
     * When the counter is exhausted it acquires the refresh lock and fetches a
     * new block; concurrent threads wait at the lock boundary.
     */
    private long nextCounter() {
        // Fast path: most calls go here without contention
        long value = counter.getAndIncrement();
        if (value < blockEnd) {
            return value;
        }

        // Slow path: block exhausted — one thread refreshes, others wait
        refreshLock.lock();
        try {
            // Double-check: another thread may have already refreshed
            value = counter.getAndIncrement();
            if (value < blockEnd) {
                return value;
            }

            // Allocate a new block
            KeyBlock newBlock = blockAllocator.allocateBlock();
            log.info("Loaded new block: {}", newBlock);
            currentBlock = newBlock;
            blockEnd = newBlock.end();

            // Reset counter to the block start; hand out the first value immediately
            counter.set(newBlock.start() + 1);
            return newBlock.start();
        } finally {
            refreshLock.unlock();
        }
    }
}
