package com.urlshortener.kgs.service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.redisson.api.RBatch;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.urlshortener.kgs.config.KgsProperties;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import jakarta.annotation.PostConstruct;

/**
 * Allocates exclusive key blocks from a distributed etcd counter.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Read current counter value from etcd key {@code /kgs/{region}/counter}.
 *   <li>Use a Compare-And-Swap (CAS) transaction to atomically replace the
 *       current value with {@code current + blockSize}.  The transaction
 *       succeeds only if the value has not changed since the read.
 *   <li>If the CAS fails (another pod won the race), retry up to
 *       {@link #MAX_RETRIES} times with exponential back-off.
 *   <li>After a successful allocation the block is registered in RedisBloom
 *       ({@code BF.ADD kgs:allocated {key}}) asynchronously so that
 *       deduplication checks can be done at the write-service layer.
 * </ol>
 *
 * <h2>Region offsets</h2>
 * Each region's counter starts at a configured offset so that key spaces never
 * overlap globally even when two regions run concurrently.
 */
@Service
public class BlockAllocator {

    private static final Logger log = LoggerFactory.getLogger(BlockAllocator.class);

    /** Maximum CAS retry attempts before propagating the failure. */
    private static final int MAX_RETRIES = 3;

    /** Initial back-off in ms; doubles on each retry. */
    private static final long INITIAL_BACKOFF_MS = 50L;

    /** RedisBloom filter key under which allocated counters are tracked. */
    private static final String BLOOM_FILTER_KEY = "kgs:allocated";

    /** etcd operation timeout. */
    private static final long ETCD_TIMEOUT_SECONDS = 5L;

    private final KV kvClient;
    private final RedissonClient redisson;
    private final KgsProperties props;

    /** Cached warm block — pre-allocated at startup to serve the first request instantly. */
    private volatile KeyBlock warmBlock;

    public BlockAllocator(KV kvClient, RedissonClient redisson, KgsProperties props) {
        this.kvClient = kvClient;
        this.redisson = redisson;
        this.props = props;
    }

    // ── Startup warm-up ───────────────────────────────────────────────────────

    /**
     * Pre-allocates one block at startup so the first {@link #allocateBlock()}
     * call returns immediately without waiting for an etcd round trip.
     */
    @PostConstruct
    public void warmUp() {
        log.info("KGS warm-up: pre-allocating one block for region={}", props.getRegion());
        try {
            warmBlock = allocateBlock();
            log.info("Warm block ready: {}", warmBlock);
        } catch (Exception e) {
            // Non-fatal: the first real request will allocate on demand.
            log.warn("Warm-up block allocation failed (will retry on first request): {}", e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the pre-warmed block if available and unused, otherwise allocates
     * a fresh block from etcd using CAS with exponential-backoff retries.
     *
     * @return an exclusively owned {@link KeyBlock}
     * @throws BlockAllocationException if etcd is unavailable after all retries
     */
    public KeyBlock allocateBlock() {
        // Drain the warm block on first call (avoids a redundant etcd round trip)
        KeyBlock warm = warmBlock;
        if (warm != null) {
            warmBlock = null;
            log.debug("Serving pre-warmed block: {}", warm);
            return warm;
        }
        return allocateWithRetry();
    }

    // ── Internal allocation logic ─────────────────────────────────────────────

    private KeyBlock allocateWithRetry() {
        long backoffMs = INITIAL_BACKOFF_MS;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                KeyBlock block = attemptCasAllocation();
                if (block != null) {
                    log.info("Allocated block (attempt {}): {}", attempt, block);
                    registerInBloomAsync(block);
                    return block;
                }
                // CAS failed — another pod updated the counter; retry immediately
                log.debug("CAS failed on attempt {} (contention), retrying…", attempt);
            } catch (Exception e) {
                lastException = e;
                log.warn("etcd error on attempt {}/{}: {}", attempt, MAX_RETRIES, e.getMessage());
            }

            if (attempt < MAX_RETRIES) {
                sleep(backoffMs);
                backoffMs *= 2;
            }
        }

        throw new BlockAllocationException(
                "Failed to allocate a key block after " + MAX_RETRIES + " attempts", lastException);
    }

    /**
     * Performs one CAS attempt against etcd.
     *
     * <ol>
     *   <li>GET the current counter (or use the region offset if the key is absent).
     *   <li>TXN: if the value equals the one we read → PUT new value, else no-op.
     *   <li>If succeeded → return the block; if not → return {@code null}.
     * </ol>
     */
    private KeyBlock attemptCasAllocation() throws ExecutionException, InterruptedException, TimeoutException {
        String etcdKey = "/kgs/" + props.getRegion() + "/counter";
        ByteSequence key = utf8(etcdKey);

        // 1. Read current value
        var getResponse = kvClient
                .get(key, GetOption.DEFAULT)
                .get(ETCD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        long currentVersion;
        long currentCounter;

        if (getResponse.getKvs().isEmpty()) {
            // Key doesn't exist yet — initialise at the region offset
            currentVersion = 0L;          // version 0 → key does not exist
            currentCounter = props.regionStartOffset();
        } else {
            var kv = getResponse.getKvs().get(0);
            currentVersion = kv.getVersion();
            currentCounter = parseLong(kv.getValue());
        }

        long newCounter = currentCounter + props.getBlockSize();

        // 2. CAS: put new value only if version matches what we read
        ByteSequence currentValue = utf8(Long.toString(currentCounter));
        ByteSequence newValue = utf8(Long.toString(newCounter));

        io.etcd.jetcd.op.Cmp versionMatch;
        if (currentVersion == 0L) {
            // Key does not exist → succeed only when version == 0 (CREATE_REVISION == 0)
            versionMatch = new io.etcd.jetcd.op.Cmp(
                    key,
                    io.etcd.jetcd.op.Cmp.Op.EQUAL,
                    CmpTarget.createRevision(0L));
        } else {
            // Key exists → compare the stored value to guard against stale reads
            versionMatch = new io.etcd.jetcd.op.Cmp(
                    key,
                    io.etcd.jetcd.op.Cmp.Op.EQUAL,
                    CmpTarget.value(currentValue));
        }

        TxnResponse txnResponse = kvClient
                .txn()
                .If(versionMatch)
                .Then(Op.put(key, newValue, PutOption.DEFAULT))
                .commit()
                .get(ETCD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!txnResponse.isSucceeded()) {
            return null; // CAS failed — caller should retry
        }

        return new KeyBlock(currentCounter, newCounter, props.getRegion());
    }

    // ── RedisBloom async registration ─────────────────────────────────────────

    /**
     * Asynchronously registers every counter value in the allocated block into
     * the RedisBloom filter using a pipelined {@link RBatch}.
     *
     * <p>Failures are logged but never propagate to the caller; RedisBloom is
     * used for best-effort deduplication, not hard enforcement.
     */
    private void registerInBloomAsync(KeyBlock block) {
        CompletableFuture.runAsync(() -> {
            try {
                // Redisson RBatch pipelines all commands in a single round-trip
                RBatch batch = redisson.createBatch();
                var bf = batch.getBloomFilter(BLOOM_FILTER_KEY);

                for (long counter = block.start(); counter < block.end(); counter++) {
                    // Store the raw counter, not the encoded key, to keep BF compact
                    bf.addAsync(counter);
                }

                batch.execute();
                log.debug("Registered {} keys in RedisBloom for block {}", block.size(), block);
            } catch (Exception e) {
                log.warn("RedisBloom registration failed for block {} (non-fatal): {}",
                        block, e.getMessage());
            }
        });
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    private static ByteSequence utf8(String s) {
        return ByteSequence.from(s, StandardCharsets.UTF_8);
    }

    private static long parseLong(ByteSequence bs) {
        return Long.parseLong(bs.toString(StandardCharsets.UTF_8).trim());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BlockAllocationException("Interrupted during back-off", ie);
        }
    }

    // ── Nested exception ──────────────────────────────────────────────────────

    /**
     * Thrown when a key block cannot be allocated after all retries.
     */
    public static class BlockAllocationException extends RuntimeException {
        public BlockAllocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
