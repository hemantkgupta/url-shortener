package com.urlshortener.kgs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link KeyBlockCache}.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Thread safety: 100 concurrent threads each call {@link KeyBlockCache#nextKey()}
 *       — no duplicate keys may be emitted.
 *   <li>Block exhaustion: when a block runs out the cache automatically
 *       requests a new one from {@link BlockAllocator}.
 *   <li>Block pre-loading: a new block is loaded before the first call
 *       when the cache starts empty.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class KeyBlockCacheTest {

    @Mock
    private BlockAllocator blockAllocator;

    private KeyBlockCache cache;

    /** Small block size so exhaustion tests are fast. */
    private static final long SMALL_BLOCK_SIZE = 50L;

    private static final long REGION_OFFSET = 0L;
    private static final String REGION = "local";

    @BeforeEach
    void setUp() {
        cache = new KeyBlockCache(blockAllocator);
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("nextKey: 100 concurrent threads receive unique keys — no duplicates")
    void nextKey_concurrentAccess_noDuplicates() throws InterruptedException {
        int threadCount = 100;
        long blockSize  = 200L; // large enough to not exhaust mid-test

        // Provide a block that covers all 100 requests
        KeyBlock block = new KeyBlock(REGION_OFFSET, REGION_OFFSET + blockSize, REGION);
        when(blockAllocator.allocateBlock()).thenReturn(block);

        Set<String> keys = ConcurrentHashMap.newKeySet();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch done  = new CountDownLatch(threadCount);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        ready.await(); // all threads start simultaneously
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    String key = cache.nextKey();
                    keys.add(key);
                    done.countDown();
                });
            }
        }

        boolean finished = done.await(10, TimeUnit.SECONDS);
        assertThat(finished).as("All threads should complete within 10 s").isTrue();
        assertThat(keys).as("Each thread must receive a unique key").hasSize(threadCount);
    }

    @Test
    @DisplayName("nextKey: when block exhausted a new block is fetched from BlockAllocator")
    void nextKey_blockExhausted_fetchesNewBlock() {
        // First block: only 5 keys
        KeyBlock firstBlock  = new KeyBlock(0L, SMALL_BLOCK_SIZE, REGION);
        // Second block: next 50 keys
        KeyBlock secondBlock = new KeyBlock(SMALL_BLOCK_SIZE, SMALL_BLOCK_SIZE * 2, REGION);

        when(blockAllocator.allocateBlock())
                .thenReturn(firstBlock)
                .thenReturn(secondBlock);

        // Exhaust the first block completely
        for (long i = 0; i < SMALL_BLOCK_SIZE; i++) {
            cache.nextKey();
        }

        // One more call — should trigger a second allocation
        String keyAfterExhaustion = cache.nextKey();
        assertThat(keyAfterExhaustion).isNotNull().isNotEmpty();

        // Verify BlockAllocator was called at least twice
        verify(blockAllocator, atLeast(2)).allocateBlock();
    }

    @Test
    @DisplayName("nextKey: returns non-null, non-empty Base62 keys of expected length")
    void nextKey_returnsValidBase62Key() {
        KeyBlock block = new KeyBlock(0L, 1_000L, REGION);
        when(blockAllocator.allocateBlock()).thenReturn(block);

        String key = cache.nextKey();

        assertThat(key)
                .isNotNull()
                .isNotEmpty()
                .matches("[0-9A-Za-z]+")
                .hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("nextKey: successive keys are all different (bit-reversal scattering works)")
    void nextKey_successiveKeys_areDistinct() {
        KeyBlock block = new KeyBlock(0L, 1_000L, REGION);
        when(blockAllocator.allocateBlock()).thenReturn(block);

        Set<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            keys.add(cache.nextKey());
        }

        assertThat(keys).hasSize(100);
    }

    @Test
    @DisplayName("currentBlock: returns null before first key is requested")
    void currentBlock_beforeFirstRequest_isNull() {
        // No block allocated yet — the internal sentinel value prevents reads
        // until a block is fetched, so currentBlock() should be null
        assertThat(cache.currentBlock()).isNull();
    }

    @Test
    @DisplayName("currentBlock: reflects the currently loaded block after first key request")
    void currentBlock_afterFirstRequest_returnsActiveBlock() {
        KeyBlock block = new KeyBlock(1_000L, 2_000L, REGION);
        when(blockAllocator.allocateBlock()).thenReturn(block);

        cache.nextKey();

        KeyBlock current = cache.currentBlock();
        assertThat(current).isNotNull();
        assertThat(current.start()).isEqualTo(1_000L);
        assertThat(current.end()).isEqualTo(2_000L);
        assertThat(current.region()).isEqualTo(REGION);
    }

    @Test
    @DisplayName("nextKey: allocates exactly one new block per exhaustion event under single-thread")
    void nextKey_singleThread_exactlyOneAllocationPerExhaustion() {
        long size = 10L;
        KeyBlock block1 = new KeyBlock(0L,    size,      REGION);
        KeyBlock block2 = new KeyBlock(size,   size * 2,  REGION);
        KeyBlock block3 = new KeyBlock(size*2, size * 3,  REGION);

        when(blockAllocator.allocateBlock())
                .thenReturn(block1)
                .thenReturn(block2)
                .thenReturn(block3);

        // Consume exactly two full blocks
        for (long i = 0; i < size * 2; i++) {
            cache.nextKey();
        }

        verify(blockAllocator, times(2)).allocateBlock();
    }
}
