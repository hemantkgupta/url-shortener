package com.urlshortener.analytics;

import com.urlshortener.analytics.config.AnalyticsProperties;
import com.urlshortener.analytics.flink.EmbeddedFlinkRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point for the Analytics Service.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Bootstraps the Spring Boot application context (REST API, ClickHouse schema,
 *       Redis counters).</li>
 *   <li>When {@code analytics.flink.embedded=true} (local / CI mode), the
 *       {@link EmbeddedFlinkRunner} component automatically launches an in-process
 *       Flink mini-cluster after the Spring context is fully started.  This lets
 *       developers iterate without a separate Flink cluster.</li>
 *   <li>In production ({@code analytics.flink.embedded=false}) the Flink job is
 *       submitted to a dedicated Flink cluster via {@code flink run} and the Spring
 *       Boot process only serves the REST API.</li>
 * </ol>
 */
@SpringBootApplication
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsServiceApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(AnalyticsServiceApplication.class, args);

        AnalyticsProperties props = ctx.getBean(AnalyticsProperties.class);
        if (props.getFlink().isEmbedded()) {
            log.info("Analytics Service started in embedded Flink mode — "
                    + "EmbeddedFlinkRunner will launch the mini-cluster.");
        } else {
            log.info("Analytics Service started — Flink job must be submitted to a remote cluster.");
        }
    }
}
