package com.urlshortener.redirect.service;

import com.urlshortener.core.domain.ClickEvent;
import com.urlshortener.core.domain.UrlMapping;
import com.urlshortener.core.util.IpHasher;
import com.urlshortener.redirect.config.RedirectServiceProperties;
import com.urlshortener.redirect.repository.UrlMappingRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Orchestrates the hot-path redirect flow: Bloom filter → Redis cache → ScyllaDB.
 *
 * <h2>Decision flow</h2>
 * <ol>
 *   <li>Check the Bloom filter — if definitely absent, return {@link RedirectResult.NotFound}
 *       immediately without touching the cache or DB.</li>
 *   <li>Try the Redis cache — if present and not due for an XFetch early refresh,
 *       publish a click event asynchronously and return {@link RedirectResult.Found}.</li>
 *   <li>On cache miss or XFetch refresh trigger, query ScyllaDB.
 *       If the DB returns nothing, return {@link RedirectResult.NotFound}.
 *       If the mapping is inactive or expired, return {@link RedirectResult.Gone}.</li>
 *   <li>Asynchronously warm the Redis cache and publish a click event.</li>
 *   <li>Return {@link RedirectResult.Found} with the long URL.</li>
 * </ol>
 *
 * <h2>Click event publishing</h2>
 * Click events are always published in a virtual thread so they add zero latency to
 * the redirect response.  They are NOT published for {@link RedirectResult.NotFound}
 * or {@link RedirectResult.Gone} responses.
 *
 * <h2>Metrics</h2>
 * <ul>
 *   <li>{@code redirect.cache.hits{tier="redis"}} — counter, incremented on Redis hits</li>
 *   <li>{@code redirect.cache.hits{tier="db"}} — counter, incremented on DB fallback hits</li>
 *   <li>{@code redirect.latency} — timer wrapping the full redirect method</li>
 * </ul>
 */
@Service
public class RedirectService {

    private static final Logger log = LoggerFactory.getLogger(RedirectService.class);

    private static final Duration CACHE_GET_TIMEOUT = Duration.ofMillis(10);

    private final BloomFilterService bloomFilterService;
    private final CacheService cacheService;
    private final UrlMappingRepository urlMappingRepository;
    private final ClickEventPublisher clickEventPublisher;
    private final RedirectServiceProperties properties;

    // Micrometer instruments
    private final Counter redisCacheHitCounter;
    private final Counter dbCacheHitCounter;
    private final Timer redirectLatencyTimer;

    public RedirectService(
            BloomFilterService bloomFilterService,
            CacheService cacheService,
            UrlMappingRepository urlMappingRepository,
            ClickEventPublisher clickEventPublisher,
            RedirectServiceProperties properties,
            MeterRegistry meterRegistry) {
        this.bloomFilterService = bloomFilterService;
        this.cacheService = cacheService;
        this.urlMappingRepository = urlMappingRepository;
        this.clickEventPublisher = clickEventPublisher;
        this.properties = properties;

        this.redisCacheHitCounter = Counter.builder("redirect.cache.hits")
                .tag("tier", "redis")
                .description("Redirects served from the Redis cache")
                .register(meterRegistry);
        this.dbCacheHitCounter = Counter.builder("redirect.cache.hits")
                .tag("tier", "db")
                .description("Redirects served via ScyllaDB fallback")
                .register(meterRegistry);
        this.redirectLatencyTimer = Timer.builder("redirect.latency")
                .description("End-to-end redirect resolution latency")
                .register(meterRegistry);
    }

    /**
     * Resolves a short key to its redirect destination.
     *
     * @param shortKey the Base-62 short key extracted from the request path
     * @param request  the incoming HTTP request — used to extract analytics signals
     *                 (IP, Referer, User-Agent)
     * @return a {@link RedirectResult} indicating the outcome (Found / NotFound / Gone)
     */
    public RedirectResult redirect(String shortKey, HttpServletRequest request) {
        return redirectLatencyTimer.record(() -> doRedirect(shortKey, request));
    }

    // ── Private implementation ────────────────────────────────────────────────

    private RedirectResult doRedirect(String shortKey, HttpServletRequest request) {
        // ── Step 1: Bloom filter gate ─────────────────────────────────────────
        if (!bloomFilterService.exists(shortKey)) {
            log.debug("Bloom filter rejected shortKey={}", shortKey);
            return new RedirectResult.NotFound();
        }

        // ── Step 2: Redis cache lookup ────────────────────────────────────────
        long cacheTtlSeconds = properties.getRedis().getCacheTtlSeconds();

        String cachedUrl = null;
        try {
            cachedUrl = cacheService.get(shortKey).block(CACHE_GET_TIMEOUT);
        } catch (Exception ex) {
            log.warn("Redis GET timed out or failed for shortKey={}: {}", shortKey, ex.getMessage());
        }

        boolean earlyRefresh = cachedUrl != null
                && cacheService.shouldEarlyRefresh(shortKey, cacheTtlSeconds);

        if (cachedUrl != null && !earlyRefresh) {
            // Cache hit — fast path
            redisCacheHitCounter.increment();
            log.debug("Cache hit (Redis) for shortKey={}", shortKey);
            publishClickEventAsync(shortKey, request);
            return new RedirectResult.Found(cachedUrl);
        }

        // ── Step 3: ScyllaDB fallback ─────────────────────────────────────────
        Optional<UrlMapping> mappingOpt = urlMappingRepository.findByShortKey(shortKey);

        if (mappingOpt.isEmpty()) {
            log.debug("DB miss for shortKey={}", shortKey);
            return new RedirectResult.NotFound();
        }

        UrlMapping mapping = mappingOpt.get();

        if (!mapping.isActive() || mapping.isExpired(Instant.now())) {
            log.debug("Mapping inactive or expired for shortKey={} active={} expiresAt={}",
                    shortKey, mapping.isActive(), mapping.getExpiresAt());
            return new RedirectResult.Gone();
        }

        String longUrl = mapping.getLongUrl();

        // ── Step 4: Async cache warm ──────────────────────────────────────────
        cacheService.set(shortKey, longUrl, cacheTtlSeconds)
                .subscribe(
                        null,
                        ex -> log.warn("Failed to cache shortKey={}: {}", shortKey, ex.getMessage()));

        // ── Step 5: Async click event ─────────────────────────────────────────
        dbCacheHitCounter.increment();
        log.debug("DB hit for shortKey={}", shortKey);
        publishClickEventAsync(shortKey, request);

        // ── Step 6: Return result ─────────────────────────────────────────────
        return new RedirectResult.Found(longUrl);
    }

    /**
     * Builds a {@link ClickEvent} from the request context and delegates to
     * {@link ClickEventPublisher#publish(ClickEvent)} on a virtual thread.
     *
     * <p>Geo-lookup is deferred to the Flink analytics pipeline — we only capture the
     * raw signals available in the HTTP request.
     */
    private void publishClickEventAsync(String shortKey, HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        String referrer  = request.getHeader("Referer");
        String ip        = resolveClientIp(request);
        String ipHash    = IpHasher.hashIp(ip, properties.getIpDailySalt());
        String device    = DeviceParser.parseDevice(userAgent);
        String browser   = DeviceParser.parseBrowser(userAgent);

        ClickEvent event = ClickEvent.builder()
                .shortKey(shortKey)
                .eventTime(Instant.now())
                .country("unknown")   // enriched downstream by Flink geo-lookup
                .city(null)
                .referrer(referrer)
                .device(device)
                .browser(browser)
                .ipHash(ipHash)
                .build();

        clickEventPublisher.publish(event);
    }

    /**
     * Resolves the real client IP, honouring standard reverse-proxy headers.
     *
     * <p>Checks {@code X-Forwarded-For} first, then falls back to
     * {@code HttpServletRequest#getRemoteAddr()}.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For may be a comma-separated list; the first entry is the client
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
