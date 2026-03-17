package com.urlshortener.analytics.flink;

import com.urlshortener.core.domain.ClickEvent;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;

/**
 * Flink {@link RichSinkFunction} that bulk-inserts {@link ClickEvent} records into
 * the ClickHouse {@code click_events} table using JDBC.
 *
 * <p><strong>Design decisions:</strong>
 * <ul>
 *   <li>Uses a raw JDBC {@link Connection} (not HikariCP) so that no Spring-managed
 *       connection pool is shared with the Flink task-manager classloader.</li>
 *   <li>The connection is opened lazily in {@link #open} and closed in {@link #close},
 *       matching the Flink operator lifecycle.</li>
 *   <li>Retry logic: up to {@value #MAX_RETRIES} attempts with exponential back-off
 *       before propagating the exception and letting Flink restart the task.</li>
 *   <li>Batch inserts: all events in a single {@link #invoke} call (which corresponds
 *       to one collected window) are sent in a single multi-row INSERT for efficiency.</li>
 * </ul>
 *
 * <p>The sink receives a {@code List<ClickEvent>} collected by the upstream window;
 * see {@link ClickEventFlinkJob} for how the windowing and collection work.
 */
public class ClickHouseSink extends RichSinkFunction<List<ClickEvent>> {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ClickHouseSink.class);

    private static final String INSERT_SQL =
            "INSERT INTO click_events "
            + "(short_key, event_time, country, city, referrer, device, browser, ip_hash) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500L;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    // Opened per task-manager thread; not serialized
    private transient Connection connection;

    public ClickHouseSink(String jdbcUrl, String username, String password) {
        this.jdbcUrl  = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public void open(org.apache.flink.configuration.Configuration parameters) throws Exception {
        super.open(parameters);
        connection = openConnection();
        log.info("ClickHouseSink opened JDBC connection to {}", jdbcUrl);
    }

    @Override
    public void close() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            log.info("ClickHouseSink closed JDBC connection");
        }
        super.close();
    }

    @Override
    public void invoke(List<ClickEvent> events, Context context) throws Exception {
        if (events == null || events.isEmpty()) {
            return;
        }
        insertWithRetry(events, 0, INITIAL_BACKOFF_MS);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void insertWithRetry(List<ClickEvent> events, int attempt, long backoffMs)
            throws Exception {
        try {
            ensureConnection();
            batchInsert(events);
        } catch (Exception e) {
            if (attempt >= MAX_RETRIES) {
                log.error("ClickHouseSink: max retries ({}) exceeded. Giving up on batch of {} events.",
                        MAX_RETRIES, events.size(), e);
                throw e;
            }
            log.warn("ClickHouseSink: insert attempt {} failed ({}). Retrying in {}ms…",
                    attempt + 1, e.getMessage(), backoffMs);
            Thread.sleep(backoffMs);
            // Force reconnect on next attempt
            closeQuietly();
            insertWithRetry(events, attempt + 1, backoffMs * 2);
        }
    }

    private void batchInsert(List<ClickEvent> events) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
            for (ClickEvent event : events) {
                ps.setString(1, event.getShortKey());
                ps.setTimestamp(2, Timestamp.from(event.getEventTime()));
                ps.setString(3, nullToEmpty(event.getCountry()));
                ps.setString(4, nullToEmpty(event.getCity()));
                ps.setString(5, nullToEmpty(event.getReferrer()));
                ps.setString(6, nullToEmpty(event.getDevice()));
                ps.setString(7, nullToEmpty(event.getBrowser()));
                ps.setString(8, nullToEmpty(event.getIpHash()));
                ps.addBatch();
            }
            int[] result = ps.executeBatch();
            log.debug("ClickHouseSink: inserted {} rows (batch size={})", result.length, events.size());
        }
    }

    private void ensureConnection() throws Exception {
        if (connection == null || connection.isClosed()) {
            log.info("ClickHouseSink: reconnecting to ClickHouse…");
            connection = openConnection();
        }
    }

    private Connection openConnection() throws Exception {
        // Load the ClickHouse JDBC driver explicitly (needed in shaded Flink classloader)
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // swallow — we are about to reconnect
            } finally {
                connection = null;
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
