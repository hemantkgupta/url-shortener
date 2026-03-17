package com.urlshortener.redirect.service;

import com.urlshortener.core.domain.UrlMapping;
import com.urlshortener.redirect.config.RedirectServiceProperties;
import com.urlshortener.redirect.repository.UrlMappingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedirectService}.
 *
 * <p>All dependencies are mocked with Mockito. {@link SimpleMeterRegistry} is used
 * so that Micrometer {@link io.micrometer.core.instrument.Counter} and
 * {@link io.micrometer.core.instrument.Timer} beans register without a full Spring context.
 */
@ExtendWith(MockitoExtension.class)
class RedirectServiceTest {

    private static final String SHORT_KEY  = "aB3xY9z";
    private static final String LONG_URL   = "https://www.example.com/some/very/long/path";
    private static final long   TTL_SECS   = 86400L;

    // ── Mocks ──────────────────────────────────────────────────────────────────

    @Mock private BloomFilterService       bloomFilterService;
    @Mock private CacheService             cacheService;
    @Mock private UrlMappingRepository     urlMappingRepository;
    @Mock private ClickEventPublisher      clickEventPublisher;
    @Mock private HttpServletRequest       request;

    // ── System under test ─────────────────────────────────────────────────────

    private RedirectService redirectService;
    private SimpleMeterRegistry meterRegistry;

    // ── Setup ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        RedirectServiceProperties properties = new RedirectServiceProperties();
        RedirectServiceProperties.Redis redisProps = new RedirectServiceProperties.Redis();
        redisProps.setCacheTtlSeconds(TTL_SECS);
        properties.setRedis(redisProps);
        // ipDailySalt default is "dev-salt-change-in-prod" — fine for unit tests

        redirectService = new RedirectService(
                bloomFilterService,
                cacheService,
                urlMappingRepository,
                clickEventPublisher,
                properties,
                meterRegistry);

        // Default stub for HttpServletRequest analytics headers so publishClickEventAsync
        // never throws NPE when it is invoked as a side-effect in passing tests.
        when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0");
        when(request.getHeader("Referer")).thenReturn(null);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    // ── Test cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bloomFilterRejectsUnknownKey_returnsNotFound: bloom returns false → RedirectResult.NotFound, no cache or DB call")
    void bloomFilterRejectsUnknownKey_returnsNotFound() {
        when(bloomFilterService.exists(SHORT_KEY)).thenReturn(false);

        RedirectResult result = redirectService.redirect(SHORT_KEY, request);

        assertThat(result).isInstanceOf(RedirectResult.NotFound.class);
        verify(cacheService, never()).get(anyString());
        verify(urlMappingRepository, never()).findByShortKey(anyString());
        verify(clickEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("cacheHit_returnsFound_withoutDbCall: bloom true, cache hit, no early refresh → RedirectResult.Found, repository never called")
    void cacheHit_returnsFound_withoutDbCall() {
        when(bloomFilterService.exists(SHORT_KEY)).thenReturn(true);
        when(cacheService.get(SHORT_KEY)).thenReturn(Mono.just(LONG_URL));
        when(cacheService.shouldEarlyRefresh(eq(SHORT_KEY), anyLong())).thenReturn(false);

        RedirectResult result = redirectService.redirect(SHORT_KEY, request);

        assertThat(result).isInstanceOf(RedirectResult.Found.class);
        assertThat(((RedirectResult.Found) result).longUrl()).isEqualTo(LONG_URL);
        verify(urlMappingRepository, never()).findByShortKey(anyString());

        // Redis hit counter must be incremented exactly once
        assertThat(meterRegistry.counter("redirect.cache.hits", "tier", "redis").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("cacheMiss_dbHit_returnsFound: bloom true, cache miss, DB returns active non-expired mapping → RedirectResult.Found")
    void cacheMiss_dbHit_returnsFound() {
        when(bloomFilterService.exists(SHORT_KEY)).thenReturn(true);
        // cache miss: Mono.empty() causes block() to return null
        when(cacheService.get(SHORT_KEY)).thenReturn(Mono.empty());
        when(cacheService.set(eq(SHORT_KEY), eq(LONG_URL), anyLong()))
                .thenReturn(Mono.empty());

        UrlMapping activeMapping = UrlMapping.builder()
                .shortKey(SHORT_KEY)
                .longUrl(LONG_URL)
                .createdAt(Instant.now().minusSeconds(60))
                .isActive(true)
                .build(); // expiresAt = null → never expires

        when(urlMappingRepository.findByShortKey(SHORT_KEY)).thenReturn(Optional.of(activeMapping));

        RedirectResult result = redirectService.redirect(SHORT_KEY, request);

        assertThat(result).isInstanceOf(RedirectResult.Found.class);
        assertThat(((RedirectResult.Found) result).longUrl()).isEqualTo(LONG_URL);

        verify(urlMappingRepository).findByShortKey(SHORT_KEY);

        // DB hit counter must be incremented
        assertThat(meterRegistry.counter("redirect.cache.hits", "tier", "db").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("cacheMiss_dbMiss_returnsNotFound: bloom true, cache miss, DB empty → RedirectResult.NotFound")
    void cacheMiss_dbMiss_returnsNotFound() {
        when(bloomFilterService.exists(SHORT_KEY)).thenReturn(true);
        when(cacheService.get(SHORT_KEY)).thenReturn(Mono.empty());
        when(urlMappingRepository.findByShortKey(SHORT_KEY)).thenReturn(Optional.empty());

        RedirectResult result = redirectService.redirect(SHORT_KEY, request);

        assertThat(result).isInstanceOf(RedirectResult.NotFound.class);
        verify(clickEventPublisher, never()).publish(any());
        assertThat(meterRegistry.counter("redirect.cache.hits", "tier", "db").count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("cacheMiss_inactiveMapping_returnsGone: bloom true, cache miss, DB returns inactive mapping → RedirectResult.Gone")
    void cacheMiss_inactiveMapping_returnsGone() {
        when(bloomFilterService.exists(SHORT_KEY)).thenReturn(true);
        when(cacheService.get(SHORT_KEY)).thenReturn(Mono.empty());

        UrlMapping inactiveMapping = UrlMapping.builder()
                .shortKey(SHORT_KEY)
                .longUrl(LONG_URL)
                .createdAt(Instant.now().minusSeconds(120))
                .isActive(false)    // explicitly deactivated
                .build();

        when(urlMappingRepository.findByShortKey(SHORT_KEY)).thenReturn(Optional.of(inactiveMapping));

        RedirectResult result = redirectService.redirect(SHORT_KEY, request);

        assertThat(result).isInstanceOf(RedirectResult.Gone.class);
        verify(clickEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("cacheMiss_expiredMapping_returnsGone: bloom true, cache miss, DB returns expired mapping → RedirectResult.Gone")
    void cacheMiss_expiredMapping_returnsGone() {
        when(bloomFilterService.exists(SHORT_KEY)).thenReturn(true);
        when(cacheService.get(SHORT_KEY)).thenReturn(Mono.empty());

        UrlMapping expiredMapping = UrlMapping.builder()
                .shortKey(SHORT_KEY)
                .longUrl(LONG_URL)
                .createdAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600)) // expired one hour ago
                .isActive(true)
                .build();

        when(urlMappingRepository.findByShortKey(SHORT_KEY)).thenReturn(Optional.of(expiredMapping));

        RedirectResult result = redirectService.redirect(SHORT_KEY, request);

        assertThat(result).isInstanceOf(RedirectResult.Gone.class);
        verify(clickEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("earlyRefresh_triggersDbLookup: bloom true, cache returns url but shouldEarlyRefresh=true → DB is called, result is Found")
    void earlyRefresh_triggersDbLookup() {
        when(bloomFilterService.exists(SHORT_KEY)).thenReturn(true);
        when(cacheService.get(SHORT_KEY)).thenReturn(Mono.just(LONG_URL));
        // XFetch early refresh is triggered
        when(cacheService.shouldEarlyRefresh(eq(SHORT_KEY), anyLong())).thenReturn(true);
        when(cacheService.set(eq(SHORT_KEY), eq(LONG_URL), anyLong()))
                .thenReturn(Mono.empty());

        UrlMapping activeMapping = UrlMapping.builder()
                .shortKey(SHORT_KEY)
                .longUrl(LONG_URL)
                .createdAt(Instant.now().minusSeconds(60))
                .isActive(true)
                .build();

        when(urlMappingRepository.findByShortKey(SHORT_KEY)).thenReturn(Optional.of(activeMapping));

        RedirectResult result = redirectService.redirect(SHORT_KEY, request);

        // DB must have been consulted because shouldEarlyRefresh returned true
        verify(urlMappingRepository).findByShortKey(SHORT_KEY);

        // Result is still Found — the early-refresh path returns the DB's long URL
        assertThat(result).isInstanceOf(RedirectResult.Found.class);
        assertThat(((RedirectResult.Found) result).longUrl()).isEqualTo(LONG_URL);

        // The early-refresh DB path increments the DB hit counter, not the Redis counter
        assertThat(meterRegistry.counter("redirect.cache.hits", "tier", "redis").count())
                .isEqualTo(0.0);
        assertThat(meterRegistry.counter("redirect.cache.hits", "tier", "db").count())
                .isEqualTo(1.0);
    }
}
