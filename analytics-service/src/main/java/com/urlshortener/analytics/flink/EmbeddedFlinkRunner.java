package com.urlshortener.analytics.flink;

import com.urlshortener.analytics.config.AnalyticsProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Spring-managed component that starts an in-process Flink local environment
 * and submits the {@link ClickEventFlinkJob} pipeline when
 * {@code analytics.flink.embedded=true}.
 *
 * <p>The Flink job is executed in a dedicated background thread so that the
 * Spring application context can finish starting (and serve HTTP requests)
 * while Flink is consuming from Kafka.
 *
 * <p>In production, this bean is excluded by the
 * {@link ConditionalOnProperty} guard; the Flink job is submitted separately
 * to a Flink cluster via {@code flink run}.
 *
 * <h2>Local environment notes</h2>
 * <ul>
 *   <li>Uses {@link StreamExecutionEnvironment#createLocalEnvironmentWithWebUI} so
 *       the Flink Web UI is available at {@code http://localhost:8081} during local
 *       development (configured via {@link RestOptions#PORT}).</li>
 *   <li>Parallelism is set to 1 for the embedded environment to minimise resource
 *       consumption on developer machines.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "analytics.flink.embedded", havingValue = "true", matchIfMissing = true)
public class EmbeddedFlinkRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedFlinkRunner.class);

    private final AnalyticsProperties properties;

    private ExecutorService flinkThread;
    private Future<?> flinkFuture;

    public EmbeddedFlinkRunner(AnalyticsProperties properties) {
        this.properties = properties;
    }

    /**
     * Launches the Flink pipeline in a background virtual thread after the Spring
     * context is fully initialised.
     */
    @PostConstruct
    public void start() {
        log.info("Starting embedded Flink local environment…");

        // Use a single virtual thread — the Flink job is I/O-heavy and unbounded.
        flinkThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "embedded-flink-runner");
            t.setDaemon(true);
            return t;
        });

        flinkFuture = flinkThread.submit(() -> {
            try {
                Configuration flinkConfig = new Configuration();
                // Use a fixed port for the embedded Web UI so it doesn't collide
                // with the Spring Boot port (8083).
                flinkConfig.setInteger(RestOptions.PORT, 8081);

                StreamExecutionEnvironment env =
                        StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(flinkConfig);
                env.setParallelism(1);

                ClickEventFlinkJob.createPipeline(env, properties);

                log.info("Embedded Flink environment created — executing pipeline…");
                env.execute("url-shortener-click-analytics-embedded");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.info("Embedded Flink runner interrupted — shutting down.");
            } catch (Exception e) {
                log.error("Embedded Flink job terminated with exception", e);
            }
        });

        log.info("Embedded Flink pipeline submitted to background thread.");
    }

    /**
     * Cancels the Flink job and shuts down the background thread on Spring
     * context shutdown.
     */
    @PreDestroy
    public void stop() {
        log.info("Stopping embedded Flink runner…");
        if (flinkFuture != null) {
            flinkFuture.cancel(true);
        }
        if (flinkThread != null) {
            flinkThread.shutdownNow();
        }
        log.info("Embedded Flink runner stopped.");
    }
}
