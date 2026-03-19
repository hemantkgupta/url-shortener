package com.urlshortener.kgs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.Mockito.doReturn;

import com.urlshortener.kgs.config.KgsProperties;
import com.urlshortener.kgs.service.BlockAllocator.BlockAllocationException;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.Txn;

/**
 * Unit tests for {@link BlockAllocator}.
 *
 * <p>The jetcd {@link KV} client is mocked with Mockito so these tests run
 * without a real etcd cluster.
 *
 * <p>Note: {@link BlockAllocator#warmUp()} is intentionally NOT called during
 * setup so that {@link BlockAllocator#allocateBlock()} always triggers a live
 * allocation path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlockAllocatorTest {

    // ── Mocks ─────────────────────────────────────────────────────────────────

    @Mock
    private KV kvClient;

    @Mock
    private org.redisson.api.RedissonClient redisson;

    @Mock
    private org.redisson.api.RBloomFilter<Long> bloomFilter;

    @Mock
    private GetResponse getResponse;

    @Mock
    private TxnResponse txnResponse;

    @Mock
    private Txn txn;

    @Mock
    private KeyValue keyValue;

    // ── System under test ─────────────────────────────────────────────────────

    private BlockAllocator blockAllocator;
    private KgsProperties kgsProperties;

    private static final long BLOCK_SIZE = 1_000L;
    private static final String REGION    = "us-east";
    private static final long   OFFSET    = 1_000_000_000_000L;

    @BeforeEach
    void setUp() {
        kgsProperties = new KgsProperties();
        kgsProperties.setBlockSize(BLOCK_SIZE);
        kgsProperties.setRegion(REGION);
        kgsProperties.getRegionOffsets().put(REGION, OFFSET);

        blockAllocator = new BlockAllocator(kvClient, redisson, kgsProperties);
        // Do NOT call warmUp() — we want allocateBlock() to go through the live path
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("allocateBlock: first allocation uses region offset as counter start")
    void allocateBlock_firstAllocation_usesRegionOffset() throws Exception {
        // Arrange: etcd key does not exist yet (empty list)
        when(kvClient.get(any(ByteSequence.class), any()))
                .thenReturn(CompletableFuture.completedFuture(getResponse));
        when(getResponse.getKvs()).thenReturn(List.of());

        // CAS transaction succeeds
        stubSuccessfulTxn();

        // Bloom filter pipeline
        stubBloomFilter();

        // Act
        KeyBlock block = blockAllocator.allocateBlock();

        // Assert: start is the region offset, end = offset + blockSize
        assertThat(block.start()).isEqualTo(OFFSET);
        assertThat(block.end()).isEqualTo(OFFSET + BLOCK_SIZE);
        assertThat(block.region()).isEqualTo(REGION);
        assertThat(block.size()).isEqualTo(BLOCK_SIZE);
    }

    @Test
    @DisplayName("allocateBlock: increments existing counter by blockSize")
    void allocateBlock_existingCounter_incrementsByBlockSize() throws Exception {
        // Arrange: etcd returns current counter = 1_000_000_000_500L
        long currentCounter = OFFSET + 500L;
        stubExistingKvWithValue(currentCounter);

        // CAS succeeds
        stubSuccessfulTxn();

        // Bloom filter pipeline
        stubBloomFilter();

        // Act
        KeyBlock block = blockAllocator.allocateBlock();

        // Assert: block starts where old counter left off
        assertThat(block.start()).isEqualTo(currentCounter);
        assertThat(block.end()).isEqualTo(currentCounter + BLOCK_SIZE);
        assertThat(block.size()).isEqualTo(BLOCK_SIZE);
    }

    @Test
    @DisplayName("allocateBlock: returned block has correct non-overlapping range")
    void allocateBlock_blockRangeIsCorrect() throws Exception {
        long currentCounter = OFFSET + 10_000L;
        stubExistingKvWithValue(currentCounter);
        stubSuccessfulTxn();
        stubBloomFilter();

        KeyBlock block = blockAllocator.allocateBlock();

        // Verify the range is exactly [start, start + blockSize)
        assertThat(block.end() - block.start()).isEqualTo(BLOCK_SIZE);
        assertThat(block.start()).isLessThan(block.end());
    }

    @Test
    @DisplayName("allocateBlock: retries when first attempt returns etcd error, succeeds on second")
    void allocateBlock_retriesOnEtcdException_succeedsOnSecondAttempt() throws Exception {
        // First GET call throws a simulated etcd connectivity error
        when(kvClient.get(any(ByteSequence.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("etcd timeout")))
                .thenReturn(CompletableFuture.completedFuture(getResponse));

        // Second attempt: key exists with a known counter
        when(getResponse.getKvs()).thenReturn(List.of(keyValue));
        when(keyValue.getVersion()).thenReturn(1L);
        when(keyValue.getValue())
                .thenReturn(ByteSequence.from(Long.toString(OFFSET), StandardCharsets.UTF_8));

        stubSuccessfulTxn();
        stubBloomFilter();

        // Act — should succeed despite the first failure
        KeyBlock block = blockAllocator.allocateBlock();

        assertThat(block.start()).isEqualTo(OFFSET);
        assertThat(block.size()).isEqualTo(BLOCK_SIZE);

        // Verify that kvClient.get() was called twice (first fail + one retry)
        verify(kvClient, times(2)).get(any(ByteSequence.class), any());
    }

    @Test
    @DisplayName("allocateBlock: throws BlockAllocationException after all retries exhausted")
    void allocateBlock_allRetriesFail_throwsBlockAllocationException() {
        // All GET calls throw exceptions
        when(kvClient.get(any(ByteSequence.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("etcd down")));

        assertThatThrownBy(() -> blockAllocator.allocateBlock())
                .isInstanceOf(BlockAllocationException.class)
                .hasMessageContaining("Failed to allocate a key block after");

        // Verify that we retried MAX_RETRIES (3) times
        verify(kvClient, times(3)).get(any(ByteSequence.class), any());
    }

    // ── Stub helpers ──────────────────────────────────────────────────────────

    private void stubExistingKvWithValue(long counterValue) throws Exception {
        when(kvClient.get(any(ByteSequence.class), any()))
                .thenReturn(CompletableFuture.completedFuture(getResponse));
        when(getResponse.getKvs()).thenReturn(List.of(keyValue));
        when(keyValue.getVersion()).thenReturn(1L);
        when(keyValue.getValue())
                .thenReturn(ByteSequence.from(Long.toString(counterValue), StandardCharsets.UTF_8));
    }

    private void stubSuccessfulTxn() {
        when(kvClient.txn()).thenReturn(txn);
        when(txn.If(any())).thenReturn(txn);
        when(txn.Then(any())).thenReturn(txn);
        when(txn.commit()).thenReturn(CompletableFuture.completedFuture(txnResponse));
        when(txnResponse.isSucceeded()).thenReturn(true);
    }

    private void stubBloomFilter() {
        doReturn(bloomFilter).when(redisson).getBloomFilter(any(String.class));
        // bf.add(Long) returns boolean — Mockito default (false) is fine; no NPE
    }
}
