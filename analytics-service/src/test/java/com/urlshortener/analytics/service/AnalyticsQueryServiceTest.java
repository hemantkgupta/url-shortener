package com.urlshortener.analytics.service;

import com.urlshortener.core.dto.AnalyticsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnalyticsQueryService}.
 *
 * <p>All external dependencies (Redis via {@link RedissonClient}, ClickHouse via
 * {@link JdbcTemplate}) are mocked with Mockito so no infrastructure is required.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsQueryServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private JdbcTemplate clickHouseJdbcTemplate;

    @Mock
    private RAtomicLong atomicLong;

    private AnalyticsQueryService service;

    private static final String SHORT_KEY = "abc1234";
    private static final Instant NOW = Instant.parse("2025-06-15T12:00:00Z");
    private static final Instant FROM = NOW.minus(30, ChronoUnit.DAYS);

    @BeforeEach
    void setUp() {
        service = new AnalyticsQueryService(redissonClient, clickHouseJdbcTemplate);
    }

    // ── getClickCount / Redis hit ─────────────────────────────────────────────

    @Test
    @DisplayName("getAnalytics — totalClicks sourced from Redis when key is present")
    void getAnalytics_totalClicksFromRedis_whenKeyPresent() {
        // Given: Redis holds a counter value of 42
        when(redissonClient.getAtomicLong("click_count:" + SHORT_KEY)).thenReturn(atomicLong);
        when(atomicLong.get()).thenReturn(42L);

        // Given: ClickHouse returns empty maps for all breakdown queries
        stubClickHouseEmpty();

        // When
        AnalyticsResponse response = service.getAnalytics(SHORT_KEY, FROM, NOW, "day");

        // Then
        assertThat(response.getTotalClicks()).isEqualTo(42L);
        assertThat(response.getShortKey()).isEqualTo(SHORT_KEY);
        verify(redissonClient).getAtomicLong("click_count:" + SHORT_KEY);
        verify(atomicLong).get();
    }

    // ── getClickCount / Redis miss / fallback to ClickHouse ──────────────────

    @Test
    @DisplayName("getAnalytics — totalClicks falls back to 0 when Redis throws an exception")
    void getAnalytics_totalClicks_fallsBackToZero_whenRedisFails() {
        // Given: Redis client throws an exception (simulates Redis being down)
        when(redissonClient.getAtomicLong(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        // Given: ClickHouse breakdown queries return empty results
        stubClickHouseEmpty();

        // When
        AnalyticsResponse response = service.getAnalytics(SHORT_KEY, FROM, NOW, "day");

        // Then: service should degrade gracefully — totalClicks = 0
        assertThat(response.getTotalClicks()).isEqualTo(0L);
        assertThat(response.getShortKey()).isEqualTo(SHORT_KEY);
    }

    @Test
    @DisplayName("getAnalytics — totalClicks is 0 when Redis counter has never been set (get() returns 0)")
    void getAnalytics_totalClicks_isZero_whenRedisCounterMissing() {
        // Given: RAtomicLong exists but its value is 0 (key was never incremented)
        when(redissonClient.getAtomicLong("click_count:" + SHORT_KEY)).thenReturn(atomicLong);
        when(atomicLong.get()).thenReturn(0L);

        stubClickHouseEmpty();

        // When
        AnalyticsResponse response = service.getAnalytics(SHORT_KEY, FROM, NOW, "day");

        // Then
        assertThat(response.getTotalClicks()).isEqualTo(0L);
    }

    // ── getAnalytics — populated breakdown maps ───────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("getAnalytics — returns AnalyticsResponse with populated breakdown maps from ClickHouse")
    void getAnalytics_returnsPopulatedBreakdownMaps() throws SQLException {
        // Given: Redis returns 100 clicks
        when(redissonClient.getAtomicLong("click_count:" + SHORT_KEY)).thenReturn(atomicLong);
        when(atomicLong.get()).thenReturn(100L);

        // Given: first ClickHouse call (clicks by day) returns two entries
        Map<String, Long> byDay = new LinkedHashMap<>();
        byDay.put("2025-06-01", 30L);
        byDay.put("2025-06-02", 70L);

        // Given: second call (clicks by country) returns country breakdown
        Map<String, Long> byCountry = new LinkedHashMap<>();
        byCountry.put("US", 60L);
        byCountry.put("DE", 40L);

        // Given: third call (clicks by device) returns device breakdown
        Map<String, Long> byDevice = new LinkedHashMap<>();
        byDevice.put("mobile", 55L);
        byDevice.put("desktop", 45L);

        // Given: fourth call (clicks by referrer) returns referrer breakdown
        Map<String, Long> byReferrer = new LinkedHashMap<>();
        byReferrer.put("twitter.com", 80L);
        byReferrer.put("(unknown)", 20L);

        // Stub the four ClickHouse queries in order using thenReturn chaining
        when(clickHouseJdbcTemplate.query(anyString(), any(ResultSetExtractor.class),
                eq(SHORT_KEY), any(), any()))
                .thenReturn(byDay)
                .thenReturn(byCountry)
                .thenReturn(byDevice)
                .thenReturn(byReferrer);

        // When
        AnalyticsResponse response = service.getAnalytics(SHORT_KEY, FROM, NOW, "day");

        // Then — structure
        assertThat(response.getShortKey()).isEqualTo(SHORT_KEY);
        assertThat(response.getTotalClicks()).isEqualTo(100L);
        assertThat(response.getPeriodFrom()).isEqualTo(FROM);
        assertThat(response.getPeriodTo()).isEqualTo(NOW);

        // Then — breakdown maps are populated
        assertThat(response.getClicksByDay()).containsEntry("2025-06-01", 30L)
                .containsEntry("2025-06-02", 70L);
        assertThat(response.getClicksByCountry()).containsEntry("US", 60L)
                .containsEntry("DE", 40L);
        assertThat(response.getClicksByDevice()).containsEntry("mobile", 55L)
                .containsEntry("desktop", 45L);
        assertThat(response.getClicksByReferrer()).containsEntry("twitter.com", 80L)
                .containsEntry("(unknown)", 20L);
    }

    @Test
    @DisplayName("getAnalytics — returns empty maps when ClickHouse query throws an exception")
    void getAnalytics_returnsEmptyMaps_whenClickHouseFails() {
        // Given: Redis returns a value
        when(redissonClient.getAtomicLong("click_count:" + SHORT_KEY)).thenReturn(atomicLong);
        when(atomicLong.get()).thenReturn(5L);

        // Given: ClickHouse is unavailable
        when(clickHouseJdbcTemplate.query(anyString(), any(ResultSetExtractor.class),
                eq(SHORT_KEY), any(), any()))
                .thenThrow(new RuntimeException("ClickHouse connection refused"));

        // When
        AnalyticsResponse response = service.getAnalytics(SHORT_KEY, FROM, NOW, "day");

        // Then: breakdown maps are empty but the response is still returned (graceful degradation)
        assertThat(response.getTotalClicks()).isEqualTo(5L);
        assertThat(response.getClicksByDay()).isEmpty();
        assertThat(response.getClicksByCountry()).isEmpty();
        assertThat(response.getClicksByDevice()).isEmpty();
        assertThat(response.getClicksByReferrer()).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Stubs all four ClickHouse breakdown queries to return an empty map.
     * Keeps individual test bodies focused on what they actually care about.
     */
    @SuppressWarnings("unchecked")
    private void stubClickHouseEmpty() {
        when(clickHouseJdbcTemplate.query(anyString(), any(ResultSetExtractor.class),
                eq(SHORT_KEY), any(), any()))
                .thenReturn(Map.of());
    }
}
