package com.urlshortener.write.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.core.domain.UrlMapping;
import com.urlshortener.core.dto.ShortenRequest;
import com.urlshortener.core.dto.ShortenResponse;
import com.urlshortener.write.cdn.CdnPurgeService;
import com.urlshortener.write.config.KgsClient;
import com.urlshortener.write.config.WriteServiceProperties;
import com.urlshortener.write.exception.AliasConflictException;
import com.urlshortener.write.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Orchestrates the full URL-shortening write path.
 *
 * <h2>Steps</h2>
 * <ol>
 *   <li>Validate and normalise the long URL via {@link UrlValidationService}</li>
 *   <li>If a custom key is requested, check the alias table for conflicts</li>
 *   <li>Obtain the short key from KGS (or use the custom key directly)</li>
 *   <li>Build and persist the {@link UrlMapping} to ScyllaDB</li>
 *   <li>If a custom key: save the alias mapping</li>
 *   <li>Asynchronously pre-warm Redis and add to Bloom filter (fire-and-forget)</li>
 *   <li>Asynchronously publish a {@code url.created} Kafka event</li>
 *   <li>Return a {@link ShortenResponse} to the controller</li>
 * </ol>
 *
 * <h2>Delete path</h2>
 * On deletion the mapping is removed from ScyllaDB, a cache-invalidation Kafka event is
 * published, and {@link CdnPurgeService#purgeByTag} is called to immediately evict the
 * stale redirect from all CDN edge nodes.  Without this, a deleted URL would continue
 * redirecting users for up to 1 hour (the CDN TTL) — a phishing / redirect-hijack risk.
 */
@Service
public class WriteService {

    private static final Logger log = LoggerFactory.getLogger(WriteService.class);

    /** Default link lifetime when the caller does not specify {@code ttlDays}. */
    private static final int DEFAULT_TTL_DAYS = 730; // ~2 years

    private final UrlValidationService    validationService;
    private final KgsClient               kgsClient;
    private final UrlMappingRepository    repository;
    private final CacheWarmupService      cacheWarmupService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final WriteServiceProperties  properties;
    private final ObjectMapper            objectMapper;
    private final CdnPurgeService         cdnPurgeService;

    public WriteService(
            UrlValidationService validationService,
            KgsClient kgsClient,
            UrlMappingRepository repository,
            CacheWarmupService cacheWarmupService,
            KafkaTemplate<String, String> kafkaTemplate,
            WriteServiceProperties properties,
            ObjectMapper objectMapper,
            CdnPurgeService cdnPurgeService) {
        this.validationService  = validationService;
        this.kgsClient          = kgsClient;
        this.repository         = repository;
        this.cacheWarmupService = cacheWarmupService;
        this.kafkaTemplate      = kafkaTemplate;
        this.properties         = properties;
        this.objectMapper       = objectMapper;
        this.cdnPurgeService    = cdnPurgeService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Shortens a URL and returns the result.
     *
     * @param request the shorten request; must not be null
     * @return the created {@link ShortenResponse}
     * @throws com.urlshortener.write.exception.AliasConflictException   if the custom key is taken
     * @throws com.urlshortener.write.exception.KeyGenerationException    if KGS is unavailable
     * @throws com.urlshortener.write.exception.MaliciousUrlException     if Safe Browsing blocks the URL
     * @throws IllegalArgumentException if the URL is invalid
     */
    public ShortenResponse shorten(ShortenRequest request) {
        // Step 1 — Validate and normalise
        String normalizedUrl = validationService.validateAndNormalize(request.getLongUrl());

        // Step 2 — Check custom-alias conflict
        String customKey = request.getCustomKey();
        if (customKey != null && !customKey.isBlank()) {
            if (repository.existsAlias(customKey)) {
                throw new AliasConflictException(customKey);
            }
        }

        // Step 3 — Resolve short key
        String shortKey = (customKey != null && !customKey.isBlank())
                ? customKey
                : kgsClient.nextKey();

        log.info("Creating short URL: shortKey={}, customKey={}", shortKey, customKey);

        // Step 4 — Build domain object
        Instant now       = Instant.now();
        int ttlDays       = request.getTtlDays() != null ? request.getTtlDays() : DEFAULT_TTL_DAYS;
        Instant expiresAt = request.getTtlDays() != null
                ? now.plus(ttlDays, ChronoUnit.DAYS)
                : now.plus(DEFAULT_TTL_DAYS, ChronoUnit.DAYS);

        UrlMapping mapping = UrlMapping.builder()
                .shortKey(shortKey)
                .longUrl(normalizedUrl)
                .userId(null)            // authentication stub — always anonymous for now
                .createdAt(now)
                .expiresAt(expiresAt)
                .isActive(true)
                .build();

        // Step 5 — Persist to ScyllaDB
        repository.save(mapping);

        // Step 6 — Persist alias mapping if custom key
        if (customKey != null && !customKey.isBlank()) {
            repository.saveAlias(customKey, shortKey, null);
        }

        // Step 7 — Async: pre-warm Redis cache (fire-and-forget)
        long ttlSeconds = expiresAt.getEpochSecond() - now.getEpochSecond();
        cacheWarmupService.warmCache(shortKey, normalizedUrl, ttlSeconds);

        // Step 8 — Async: add to Bloom filter (fire-and-forget)
        cacheWarmupService.addToBloomFilter(shortKey);

        // Step 9 — Async: publish Kafka event
        publishUrlCreatedEvent(shortKey, normalizedUrl, expiresAt, now);

        // Step 10 — Build and return response
        String shortUrl = buildShortUrl(shortKey);
        ShortenResponse response = ShortenResponse.of(shortUrl, shortKey, normalizedUrl, expiresAt, now);

        log.info("Short URL created: shortUrl={}", shortUrl);
        return response;
    }

    /**
     * Deletes a URL mapping and invalidates all cache layers.
     *
     * <p>Invalidation order:
     * <ol>
     *   <li>ScyllaDB row deleted (source of truth)</li>
     *   <li>CDN edge cache purged via {@link CdnPurgeService} — immediate eviction from
     *       Cloudflare/Fastly PoPs so the stale 302 stops being served within seconds.
     *       Without this, deleted URLs would continue redirecting for up to 1 hour.</li>
     *   <li>Kafka {@code url.created} cache-invalidation event published — downstream
     *       consumers (e.g. analytics) can react to the deletion.</li>
     * </ol>
     *
     * <p>Redis TTL-based expiry handles the L2 cache; an explicit Redis DEL is not issued
     * here because the Redis TTL is already set to 24 hours and the CDN purge is the
     * critical path for stopping active redirects.
     *
     * @param shortKey the short key to delete
     */
    public void delete(String shortKey) {
        log.info("Deleting short URL: shortKey={}", shortKey);

        // Step 1 — Remove from ScyllaDB (source of truth)
        repository.deleteByShortKey(shortKey);

        // Step 2 — Purge CDN edge cache immediately (prevents stale redirects for up to 1h)
        String cacheTag  = "url-" + shortKey;
        String shortUrl  = buildShortUrl(shortKey);
        cdnPurgeService.purgeByTag(cacheTag);
        cdnPurgeService.purgeByUrl(shortUrl);

        // Step 3 — Publish Kafka cache-invalidation event for downstream consumers
        publishCacheInvalidationEvent(shortKey);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildShortUrl(String shortKey) {
        String domain = properties.getOwnDomain();
        // Use https for non-localhost domains
        String scheme = "localhost".equalsIgnoreCase(domain) ? "http" : "https";
        return scheme + "://" + domain + "/" + shortKey;
    }

    private void publishUrlCreatedEvent(
            String shortKey, String longUrl, Instant expiresAt, Instant createdAt) {
        try {
            String topic = properties.getKafka().getTopic().getUrlCreated();
            String payload = objectMapper.writeValueAsString(Map.of(
                    "shortKey",  shortKey,
                    "longUrl",   longUrl,
                    "expiresAt", expiresAt.toString(),
                    "createdAt", createdAt.toString()
            ));
            kafkaTemplate.send(topic, shortKey, payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish url.created event for shortKey={}: {}",
                                    shortKey, ex.getMessage());
                        } else {
                            log.debug("Published url.created event: shortKey={}, topic={}",
                                    shortKey, topic);
                        }
                    });
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialise url.created event for shortKey={}: {}",
                    shortKey, ex.getMessage());
        }
    }

    private void publishCacheInvalidationEvent(String shortKey) {
        try {
            String topic = properties.getKafka().getTopic().getUrlCreated();
            String payload = objectMapper.writeValueAsString(Map.of(
                    "shortKey", shortKey,
                    "action",   "DELETE"
            ));
            kafkaTemplate.send(topic, shortKey, payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish cache-invalidation event for shortKey={}: {}",
                                    shortKey, ex.getMessage());
                        }
                    });
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialise cache-invalidation event for shortKey={}: {}",
                    shortKey, ex.getMessage());
        }
    }
}
