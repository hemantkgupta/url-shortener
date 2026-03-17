package com.urlshortener.analytics.flink;

import com.urlshortener.analytics.config.AnalyticsProperties;
import com.urlshortener.core.domain.ClickEvent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines the Flink streaming pipeline for click-event analytics.
 *
 * <p>The static {@link #createPipeline} method is called both by
 * {@link EmbeddedFlinkRunner} (local / CI mode) and by the standalone
 * {@code main} method (for production cluster submission via {@code flink run}).
 *
 * <h2>Pipeline topology</h2>
 * <pre>
 * KafkaSource (click.events, StringDeserializer)
 *   └─► Map: JSON bytes → ClickEvent          (via ClickEventDeserializationSchema)
 *       └─► filter: drop null (bad JSON)
 *           └─► keyBy(shortKey)
 *               └─► TumblingProcessingTimeWindow(windowSizeSeconds)
 *                   ├─► ClickHouseSink   (bulk JDBC INSERT)
 *                   └─► RedisCounterSink (INCR per event in the window list)
 * </pre>
 *
 * <h2>Window vs. per-event sinks</h2>
 * <ul>
 *   <li>The ClickHouse sink receives a {@code List<ClickEvent>} per window/key
 *       for efficient batch inserts.</li>
 *   <li>The Redis sink receives individual {@link ClickEvent} objects so it can
 *       pipeline INCR commands without materialising the full list again.</li>
 * </ul>
 *
 * <h2>Flink / Jackson shading</h2>
 * Deserialization uses {@link ClickEventDeserializationSchema} which creates its
 * own {@code ObjectMapper} in {@code open()} — avoiding interference with Flink's
 * shaded Jackson classes present in {@code flink-shaded-jackson}.
 */
public class ClickEventFlinkJob {

    private static final Logger log = LoggerFactory.getLogger(ClickEventFlinkJob.class);

    private ClickEventFlinkJob() {
        // utility class — not instantiated
    }

    /**
     * Builds and attaches the complete Flink pipeline to the given
     * {@link StreamExecutionEnvironment}.
     *
     * <p>Callers are responsible for calling {@code env.execute()} (or
     * submitting the job graph to a cluster); this method only wires the DAG.
     *
     * @param env   the Flink execution environment to build the pipeline on
     * @param props analytics service configuration (Kafka, ClickHouse, Redis, Flink)
     */
    public static void createPipeline(StreamExecutionEnvironment env,
                                      AnalyticsProperties props) {
        // ── Checkpointing ────────────────────────────────────────────────────
        env.enableCheckpointing(props.getFlink().getCheckpointIntervalMs());

        // ── Source: Kafka → raw bytes (deserialized to ClickEvent) ──────────
        KafkaSource<ClickEvent> kafkaSource = KafkaSource.<ClickEvent>builder()
                .setBootstrapServers(props.getKafka().getBootstrapServers())
                .setTopics(props.getKafka().getTopic().getClickEvents())
                .setGroupId(props.getKafka().getGroupId())
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new ClickEventDeserializationSchema())
                .build();

        DataStream<ClickEvent> clickEvents = env
                .fromSource(kafkaSource, WatermarkStrategy.noWatermarks(), "kafka-click-events")
                .filter(event -> event != null)
                .name("filter-null-events");

        // ── Window: keyBy shortKey, tumbling processing-time window ──────────
        long windowSeconds = props.getFlink().getWindowSizeSeconds();

        SingleOutputStreamOperator<List<ClickEvent>> windowed = clickEvents
                .keyBy(ClickEvent::getShortKey)
                .window(TumblingProcessingTimeWindows.of(Time.seconds(windowSeconds)))
                .process(new CollectEventsWindowFunction())
                .name("collect-events-window");

        // ── Sink 1: ClickHouse bulk INSERT ───────────────────────────────────
        AnalyticsProperties.ClickHouse ch = props.getClickhouse();
        windowed.addSink(new ClickHouseSink(ch.getUrl(), ch.getUsername(), ch.getPassword()))
                .name("clickhouse-sink");

        // ── Sink 2: Redis per-event INCR (flatten window list first) ─────────
        AnalyticsProperties.Redis redis = props.getRedis();
        windowed
                .flatMap((List<ClickEvent> events,
                          org.apache.flink.util.Collector<ClickEvent> out) -> {
                    for (ClickEvent e : events) {
                        out.collect(e);
                    }
                })
                .returns(ClickEvent.class)
                .name("flatten-for-redis")
                .addSink(new RedisCounterSink(redis.getHost(), redis.getPort()))
                .name("redis-counter-sink");

        log.info("ClickEventFlinkJob pipeline created — topic={}, window={}s",
                props.getKafka().getTopic().getClickEvents(), windowSeconds);
    }

    /**
     * Standalone entry point for submitting the Flink job to a remote cluster
     * via {@code flink run -c com.urlshortener.analytics.flink.ClickEventFlinkJob app.jar}.
     *
     * <p>In production, {@code analytics.flink.embedded} should be {@code false}
     * and this main method is used instead of {@link EmbeddedFlinkRunner}.
     */
    public static void main(String[] args) throws Exception {
        // When run standalone, properties are loaded from System properties /
        // environment variables injected at submission time.
        AnalyticsProperties props = buildPropertiesFromEnv();
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        createPipeline(env, props);
        env.execute("url-shortener-click-analytics");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds minimal {@link AnalyticsProperties} from environment variables when
     * running outside of Spring context (cluster submission mode).
     */
    private static AnalyticsProperties buildPropertiesFromEnv() {
        AnalyticsProperties props = new AnalyticsProperties();

        // Kafka
        AnalyticsProperties.Kafka kafka = new AnalyticsProperties.Kafka();
        kafka.setBootstrapServers(envOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        kafka.setGroupId(envOrDefault("KAFKA_GROUP_ID", "analytics-flink"));
        AnalyticsProperties.Kafka.Topic topic = new AnalyticsProperties.Kafka.Topic();
        topic.setClickEvents(envOrDefault("KAFKA_TOPIC_CLICK_EVENTS", "click.events"));
        kafka.setTopic(topic);
        props.setKafka(kafka);

        // Flink
        AnalyticsProperties.Flink flink = new AnalyticsProperties.Flink();
        flink.setEmbedded(false);
        flink.setCheckpointIntervalMs(Long.parseLong(
                envOrDefault("FLINK_CHECKPOINT_INTERVAL_MS", "10000")));
        flink.setWindowSizeSeconds(Long.parseLong(
                envOrDefault("FLINK_WINDOW_SIZE_SECONDS", "10")));
        props.setFlink(flink);

        // ClickHouse
        AnalyticsProperties.ClickHouse ch = new AnalyticsProperties.ClickHouse();
        ch.setUrl(envOrDefault("CLICKHOUSE_URL", "jdbc:clickhouse://localhost:8123/analytics"));
        ch.setUsername(envOrDefault("CLICKHOUSE_USER", "default"));
        ch.setPassword(envOrDefault("CLICKHOUSE_PASSWORD", ""));
        props.setClickhouse(ch);

        // Redis
        AnalyticsProperties.Redis redis = new AnalyticsProperties.Redis();
        redis.setHost(envOrDefault("REDIS_HOST", "localhost"));
        redis.setPort(Integer.parseInt(envOrDefault("REDIS_PORT", "6379")));
        props.setRedis(redis);

        return props;
    }

    private static String envOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    // ── Inner ProcessWindowFunction ───────────────────────────────────────────

    /**
     * Collects all {@link ClickEvent} records in a window into a {@code List<ClickEvent>}
     * and emits the list as a single downstream element.
     *
     * <p>This allows the {@link ClickHouseSink} to issue a single batch INSERT per
     * (shortKey, window) rather than one INSERT per event.
     */
    private static final class CollectEventsWindowFunction
            extends ProcessWindowFunction<ClickEvent, List<ClickEvent>, String, TimeWindow> {

        private static final long serialVersionUID = 1L;

        @Override
        public void process(String key,
                            Context context,
                            Iterable<ClickEvent> elements,
                            Collector<List<ClickEvent>> out) {
            List<ClickEvent> batch = new ArrayList<>();
            for (ClickEvent e : elements) {
                batch.add(e);
            }
            if (!batch.isEmpty()) {
                out.collect(batch);
            }
        }
    }
}
