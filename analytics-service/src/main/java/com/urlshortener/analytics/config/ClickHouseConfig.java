package com.urlshortener.analytics.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Spring {@link Configuration} that creates a ClickHouse {@link DataSource} and
 * a {@link JdbcTemplate} backed by HikariCP.
 *
 * <p>ClickHouse is used as the OLAP store for all historical analytics:
 * per-day click counts, breakdown by country / device / referrer, etc.
 *
 * <p>Connection pool sizing notes:
 * <ul>
 *   <li>ClickHouse performs best with a moderate number of concurrent connections;
 *       more connections do not improve throughput for analytics workloads.</li>
 *   <li>Flink sinks use their own JDBC connections managed externally; this pool
 *       is used exclusively by the Spring REST query path.</li>
 * </ul>
 */
@Configuration
public class ClickHouseConfig {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseConfig.class);

    private final AnalyticsProperties properties;

    public ClickHouseConfig(AnalyticsProperties properties) {
        this.properties = properties;
    }

    /**
     * HikariCP-pooled {@link DataSource} connected to ClickHouse.
     *
     * <p>The ClickHouse JDBC driver class is {@code com.clickhouse.jdbc.ClickHouseDriver}
     * (the modern {@code clickhouse-jdbc} artifact).
     */
    @Bean(destroyMethod = "close")
    public DataSource clickHouseDataSource() {
        AnalyticsProperties.ClickHouse ch = properties.getClickhouse();

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(ch.getUrl());
        hikari.setUsername(ch.getUsername());
        hikari.setPassword(ch.getPassword());
        hikari.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");

        // Conservative pool for analytics reads — ClickHouse is column-oriented
        // and handles long-running scans better than many parallel short queries.
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(2);
        hikari.setConnectionTimeout(30_000);
        hikari.setIdleTimeout(600_000);
        hikari.setMaxLifetime(1_800_000);
        hikari.setPoolName("clickhouse-pool");

        // ClickHouse-specific validation query (lightweight)
        hikari.setConnectionTestQuery("SELECT 1");

        log.info("Creating ClickHouse DataSource — url={}, user={}", ch.getUrl(), ch.getUsername());
        return new HikariDataSource(hikari);
    }

    /**
     * {@link JdbcTemplate} pre-configured for ClickHouse queries.
     *
     * <p>This bean is the primary DAO access mechanism for analytics queries
     * (clicks by day, country, device, referrer) in {@link com.urlshortener.analytics.service.AnalyticsQueryService}.
     */
    @Bean
    public JdbcTemplate clickHouseJdbcTemplate(DataSource clickHouseDataSource) {
        JdbcTemplate tpl = new JdbcTemplate(clickHouseDataSource);
        // ClickHouse scans can be slow for large date ranges; set a generous timeout.
        tpl.setQueryTimeout(60);
        return tpl;
    }
}
