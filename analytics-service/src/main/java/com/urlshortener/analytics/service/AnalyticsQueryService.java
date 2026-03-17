package com.urlshortener.analytics.service;

import com.urlshortener.core.dto.AnalyticsResponse;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service that assembles {@link AnalyticsResponse} objects for the REST API.
 *
 * <p>Data is sourced from two stores:
 * <ol>
 *   <li><strong>Redis</strong> ({@link RedissonClient} / {@code RAtomicLong}) —
 *       provides the real-time total click count that includes events processed
 *       since the last ClickHouse flush.</li>
 *   <li><strong>ClickHouse</strong> ({@link JdbcTemplate}) —
 *       provides historical breakdowns (by day, country, device, referrer)
 *       over the requested time window.</li>
 * </ol>
 *
 * <p>The {@code totalClicks} field in the response is sourced from Redis, not from
 * a ClickHouse {@code COUNT(*)} — this gives sub-millisecond freshness for the most
 * visible metric while letting ClickHouse answer the (slightly stale) breakdown queries.
 *
 * <h2>Granularity parameter</h2>
 * The {@code granularity} parameter ({@code "hour"}, {@code "day"}, {@code "week"})
 * is accepted for API compatibility but the current implementation always groups
 * by day in ClickHouse (the {@code click_counts_daily} table supports day granularity).
 * Finer or coarser granularities fall back gracefully to day-level grouping.
 */
@Service
public class AnalyticsQueryService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsQueryService.class);

    // language=SQL — clicks per day
    private static final String SQL_CLICKS_BY_DAY = """
            SELECT toDate(event_time) AS click_date, count() AS cnt
            FROM click_events
            WHERE short_key = ?
              AND event_time BETWEEN ? AND ?
            GROUP BY click_date
            ORDER BY click_date
            """;

    // language=SQL — clicks per country
    private static final String SQL_CLICKS_BY_COUNTRY = """
            SELECT country, count() AS cnt
            FROM click_events
            WHERE short_key = ?
              AND event_time BETWEEN ? AND ?
            GROUP BY country
            ORDER BY cnt DESC
            """;

    // language=SQL — clicks per device
    private static final String SQL_CLICKS_BY_DEVICE = """
            SELECT device, count() AS cnt
            FROM click_events
            WHERE short_key = ?
              AND event_time BETWEEN ? AND ?
            GROUP BY device
            ORDER BY cnt DESC
            """;

    // language=SQL — top-10 referrers
    private static final String SQL_CLICKS_BY_REFERRER = """
            SELECT referrer, count() AS cnt
            FROM click_events
            WHERE short_key = ?
              AND event_time BETWEEN ? AND ?
            GROUP BY referrer
            ORDER BY cnt DESC
            LIMIT 10
            """;

    private final RedissonClient redissonClient;
    private final JdbcTemplate clickHouseJdbcTemplate;

    public AnalyticsQueryService(RedissonClient redissonClient,
                                 JdbcTemplate clickHouseJdbcTemplate) {
        this.redissonClient       = redissonClient;
        this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
    }

    /**
     * Returns the analytics summary for {@code shortKey} over the period
     * [{@code from}, {@code to}].
     *
     * @param shortKey    the 7–8 character short key (e.g. {@code "abc1234"})
     * @param from        start of the query window (inclusive)
     * @param to          end of the query window (inclusive)
     * @param granularity desired grouping granularity — currently day-level only
     * @return fully populated {@link AnalyticsResponse}
     */
    public AnalyticsResponse getAnalytics(String shortKey,
                                          Instant from,
                                          Instant to,
                                          String granularity) {
        log.debug("getAnalytics — shortKey={}, from={}, to={}, granularity={}",
                shortKey, from, to, granularity);

        // Real-time total from Redis
        long totalClicks = fetchTotalClicksFromRedis(shortKey);

        Timestamp tsFrom = Timestamp.from(from);
        Timestamp tsTo   = Timestamp.from(to);

        Map<String, Long> byDay     = queryBreakdown(SQL_CLICKS_BY_DAY,     shortKey, tsFrom, tsTo);
        Map<String, Long> byCountry = queryBreakdown(SQL_CLICKS_BY_COUNTRY,  shortKey, tsFrom, tsTo);
        Map<String, Long> byDevice  = queryBreakdown(SQL_CLICKS_BY_DEVICE,   shortKey, tsFrom, tsTo);
        Map<String, Long> byReferrer= queryBreakdown(SQL_CLICKS_BY_REFERRER, shortKey, tsFrom, tsTo);

        return AnalyticsResponse.builder()
                .shortKey(shortKey)
                .totalClicks(totalClicks)
                .clicksByDay(byDay)
                .clicksByCountry(byCountry)
                .clicksByDevice(byDevice)
                .clicksByReferrer(byReferrer)
                .periodFrom(from)
                .periodTo(to)
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Reads the lifetime click counter for {@code shortKey} from Redis.
     *
     * <p>Returns 0 if the key does not yet exist (no clicks recorded).
     */
    private long fetchTotalClicksFromRedis(String shortKey) {
        try {
            RAtomicLong counter = redissonClient.getAtomicLong("click_count:" + shortKey);
            return counter.get();
        } catch (Exception e) {
            log.warn("Failed to read Redis counter for shortKey={}: {}", shortKey, e.getMessage());
            return 0L;
        }
    }

    /**
     * Executes a ClickHouse breakdown query and returns results as a
     * {@code LinkedHashMap} preserving the ORDER BY from the SQL.
     *
     * <p>The first column of the ResultSet is used as the map key; the second
     * ({@code cnt}) as the value.
     *
     * @param sql     parameterised SQL with exactly 3 bind parameters: shortKey, from, to
     * @param shortKey the short key to filter on
     * @param from    start timestamp
     * @param to      end timestamp
     * @return ordered map of dimension → count
     */
    private Map<String, Long> queryBreakdown(String sql,
                                             String shortKey,
                                             Timestamp from,
                                             Timestamp to) {
        try {
            return clickHouseJdbcTemplate.query(
                    sql,
                    (ResultSet rs) -> {
                        Map<String, Long> result = new LinkedHashMap<>();
                        while (rs.next()) {
                            String dimension = rs.getString(1);
                            long count       = rs.getLong(2);
                            if (dimension == null || dimension.isBlank()) {
                                dimension = "(unknown)";
                            }
                            result.merge(dimension, count, Long::sum);
                        }
                        return result;
                    },
                    shortKey, from, to);
        } catch (Exception e) {
            log.warn("ClickHouse query failed: {}", e.getMessage());
            return Map.of();
        }
    }
}
