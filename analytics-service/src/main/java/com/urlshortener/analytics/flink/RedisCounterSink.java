package com.urlshortener.analytics.flink;

import com.urlshortener.core.domain.ClickEvent;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Flink {@link RichSinkFunction} that maintains real-time click counters in Redis.
 *
 * <p>For each {@link ClickEvent} two INCR commands are issued:
 * <ol>
 *   <li>{@code INCR click_count:{shortKey}} — lifetime total</li>
 *   <li>{@code INCR click_count:{shortKey}:{yyyy-MM-dd}} — daily bucket</li>
 * </ol>
 *
 * <p><strong>Why Jedis and not Redisson?</strong>
 * Redisson brings a large classpath footprint that can conflict with Flink's
 * shaded dependencies in the task-manager classloader.  Jedis is a thin client
 * that is straightforward to shade and has no reflection-heavy initialisation.
 *
 * <p>The Jedis connection is opened once per task-manager instance in {@link #open}
 * and closed in {@link #close}, following the Flink operator lifecycle.  Reconnection
 * on broken pipe is handled via {@link #ensureConnection()}.
 *
 * <p>Events are pipelined in batches to reduce round-trips (one pipeline per
 * {@code invoke} call, which corresponds to one collected window batch).
 */
public class RedisCounterSink extends RichSinkFunction<ClickEvent> {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(RedisCounterSink.class);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final String redisHost;
    private final int redisPort;

    // Not serialized — recreated per task manager
    private transient Jedis jedis;

    public RedisCounterSink(String redisHost, int redisPort) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
    }

    @Override
    public void open(org.apache.flink.configuration.Configuration parameters) throws Exception {
        super.open(parameters);
        jedis = new Jedis(redisHost, redisPort);
        log.info("RedisCounterSink opened Jedis connection to {}:{}", redisHost, redisPort);
    }

    @Override
    public void close() throws Exception {
        if (jedis != null && jedis.isConnected()) {
            jedis.close();
            log.info("RedisCounterSink closed Jedis connection");
        }
        super.close();
    }

    @Override
    public void invoke(ClickEvent event, Context context) throws Exception {
        if (event == null) {
            return;
        }
        ensureConnection();

        String shortKey = event.getShortKey();
        String dateStr  = DATE_FMT.format(event.getEventTime());

        String totalKey = "click_count:" + shortKey;
        String dailyKey = "click_count:" + shortKey + ":" + dateStr;

        try {
            // Pipeline both INCRs in a single round-trip
            Pipeline pipeline = jedis.pipelined();
            pipeline.incr(totalKey);
            pipeline.incr(dailyKey);
            pipeline.sync();
            log.debug("RedisCounterSink: incremented {} and {}", totalKey, dailyKey);
        } catch (Exception e) {
            log.warn("RedisCounterSink: Redis INCR failed for key={} — {}", totalKey, e.getMessage());
            // Force reconnect on next event
            closeQuietly();
            throw e;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void ensureConnection() {
        if (jedis == null || !jedis.isConnected()) {
            log.info("RedisCounterSink: reconnecting to Redis {}:{}", redisHost, redisPort);
            jedis = new Jedis(redisHost, redisPort);
        }
    }

    private void closeQuietly() {
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception ignored) {
                // swallow
            } finally {
                jedis = null;
            }
        }
    }
}
