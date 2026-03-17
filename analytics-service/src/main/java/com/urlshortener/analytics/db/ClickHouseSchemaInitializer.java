package com.urlshortener.analytics.db;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ensures required ClickHouse tables exist on application startup.
 *
 * <p>All DDL statements use {@code CREATE TABLE IF NOT EXISTS} so they are
 * idempotent and safe to run on every deployment.
 *
 * <h2>Tables created</h2>
 * <dl>
 *   <dt>{@code click_events}</dt>
 *   <dd>Raw click event log, partitioned by month and ordered by
 *       {@code (short_key, event_time)} for efficient range scans per key.</dd>
 *
 *   <dt>{@code click_counts_daily}</dt>
 *   <dd>Pre-aggregated daily click counts using {@code SummingMergeTree} so
 *       ClickHouse continuously merges parts in the background, giving fast
 *       {@code SELECT SUM(click_count)} results without a full table scan.</dd>
 * </dl>
 *
 * <h2>ClickHouse engine notes</h2>
 * <ul>
 *   <li>{@code LowCardinality(String)} — dictionary-encodes values with few distinct
 *       entries (country, device, browser) for drastically smaller storage footprint.</li>
 *   <li>{@code SummingMergeTree(click_count)} — automatically sums {@code click_count}
 *       during merges for the same {@code ORDER BY} key.</li>
 * </ul>
 */
@Component
public class ClickHouseSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseSchemaInitializer.class);

    // language=SQL
    private static final String CREATE_CLICK_EVENTS = """
            CREATE TABLE IF NOT EXISTS click_events (
                short_key   LowCardinality(String),
                event_time  DateTime,
                country     LowCardinality(String),
                city        String,
                referrer    String,
                device      LowCardinality(String),
                browser     LowCardinality(String),
                ip_hash     String
            ) ENGINE = MergeTree()
            PARTITION BY toYYYYMM(event_time)
            ORDER BY (short_key, event_time)
            """;

    // language=SQL
    private static final String CREATE_CLICK_COUNTS_DAILY = """
            CREATE TABLE IF NOT EXISTS click_counts_daily (
                short_key   LowCardinality(String),
                click_date  Date,
                click_count UInt64
            ) ENGINE = SummingMergeTree(click_count)
            PARTITION BY toYYYYMM(click_date)
            ORDER BY (short_key, click_date)
            """;

    private final JdbcTemplate clickHouseJdbcTemplate;

    public ClickHouseSchemaInitializer(JdbcTemplate clickHouseJdbcTemplate) {
        this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
    }

    /**
     * Runs schema initialisation DDL after the Spring context is fully wired.
     *
     * <p>Any failure here is propagated as a {@link RuntimeException}, which will
     * prevent the Spring context from completing startup — a deliberate fail-fast
     * behaviour so broken schema is immediately visible.
     */
    @PostConstruct
    public void initialize() {
        log.info("Initialising ClickHouse schema…");
        try {
            clickHouseJdbcTemplate.execute(CREATE_CLICK_EVENTS);
            log.info("ClickHouse table 'click_events' ensured.");

            clickHouseJdbcTemplate.execute(CREATE_CLICK_COUNTS_DAILY);
            log.info("ClickHouse table 'click_counts_daily' ensured.");

            log.info("ClickHouse schema initialisation complete.");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise ClickHouse schema", e);
        }
    }
}
