package com.urlshortener.kgs.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.urlshortener.kgs.config.KgsProperties;
import com.urlshortener.kgs.service.BlockAllocator;
import com.urlshortener.kgs.service.KeyBlock;
import com.urlshortener.kgs.service.KeyBlockCache;

/**
 * Internal HTTP API exposed exclusively to other services within the cluster.
 *
 * <p>This controller is NOT fronted by a public load-balancer; it runs on
 * port 8081 and is reachable only from the cluster-internal network.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /internal/keys/next} — allocates and returns a single key.
 *       Intended for rare administrative / debugging use; bulk consumers should
 *       prefer {@code /next-block}.
 *   <li>{@code POST /internal/keys/next-block} — returns a full {@link KeyBlock}
 *       so the write-service can maintain its own per-pod counter and avoid
 *       calling KGS on every URL creation request.
 *   <li>{@code GET /internal/health} — lightweight health/status endpoint that
 *       reports the current block and region without hitting etcd.
 * </ul>
 */
@RestController
@RequestMapping("/internal/keys")
public class KeyGenerationController {

    private static final Logger log = LoggerFactory.getLogger(KeyGenerationController.class);

    private final KeyBlockCache keyBlockCache;
    private final BlockAllocator blockAllocator;
    private final KgsProperties kgsProperties;

    public KeyGenerationController(
            KeyBlockCache keyBlockCache,
            BlockAllocator blockAllocator,
            KgsProperties kgsProperties) {
        this.keyBlockCache = keyBlockCache;
        this.blockAllocator = blockAllocator;
        this.kgsProperties = kgsProperties;
    }

    /**
     * Returns the next single short key from the in-memory block cache.
     *
     * <p>If the current block is exhausted a new one is fetched from etcd
     * transparently inside {@link KeyBlockCache#nextKey()}.
     *
     * @return 200 OK with a plain-text Base-62 key (8 chars)
     */
    @PostMapping(value = "/next", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> nextKey() {
        String key = keyBlockCache.nextKey();
        log.debug("Issued single key: {}", key);
        return ResponseEntity.ok(key);
    }

    /**
     * Allocates a fresh key block and returns it to the caller.
     *
     * <p>Write-service pods call this endpoint when their local block is
     * exhausted.  They then maintain their own atomic counter over
     * {@code [startKey, endKey)} without further KGS interaction.
     *
     * @return 200 OK with {@link KeyBlockResponse} containing raw start/end
     *         counters and metadata
     */
    @PostMapping(value = "/next-block", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KeyBlockResponse> nextBlock() {
        KeyBlock block = blockAllocator.allocateBlock();
        log.info("Issued block to write-service: {}", block);

        KeyBlockResponse response = new KeyBlockResponse(
                block.start(),
                block.end(),
                block.size(),
                block.region());

        return ResponseEntity.ok(response);
    }

    /**
     * Returns a lightweight health / status document for this KGS pod.
     *
     * <p>The response includes the current cached block (if any) so that
     * operators can confirm keys are being served from the expected region and
     * counter range.  No etcd calls are made.
     *
     * @return 200 OK with a JSON map containing {@code status}, {@code region},
     *         and {@code currentBlock}
     */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> health() {
        KeyBlock current = keyBlockCache.currentBlock();

        Map<String, Object> blockInfo = current == null
                ? Map.of("allocated", false)
                : Map.of(
                        "allocated", true,
                        "start",     current.start(),
                        "end",       current.end(),
                        "size",      current.size(),
                        "region",    current.region());

        Map<String, Object> body = Map.of(
                "status",       "UP",
                "region",       kgsProperties.getRegion(),
                "currentBlock", blockInfo);

        return ResponseEntity.ok(body);
    }
}
