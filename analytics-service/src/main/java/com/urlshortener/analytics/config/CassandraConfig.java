package com.urlshortener.analytics.config;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * Spring {@link Configuration} that creates a {@link CqlSession} for the Analytics
 * Service's reads from the ScyllaDB / Cassandra cluster.
 *
 * <p>The session is used by {@link com.urlshortener.analytics.service.LinkManagementService}
 * to query the {@code url_mapping_by_user} materialized view, which supports
 * efficient pagination of a user's shortened links.
 *
 * <p>Configuration is sourced from {@link AnalyticsProperties.Cassandra}.
 */
@Configuration
public class CassandraConfig {

    private static final Logger log = LoggerFactory.getLogger(CassandraConfig.class);

    private final AnalyticsProperties properties;

    public CassandraConfig(AnalyticsProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates and returns a fully configured {@link CqlSession}.
     *
     * <p>Uses DC-aware load balancing, conservative pool sizes, and a 5-second
     * request timeout — suitable for both OLTP reads and moderate fanout queries.
     */
    @Bean(destroyMethod = "close")
    public CqlSession cqlSession() {
        AnalyticsProperties.Cassandra cass = properties.getCassandra();

        log.info("Connecting to ScyllaDB (analytics) — contactPoints={}, port={}, keyspace={}, dc={}",
                cass.getContactPoints(), cass.getPort(), cass.getKeyspace(), cass.getDatacenter());

        DriverConfigLoader configLoader = DriverConfigLoader.programmaticBuilder()
                .withString(
                        DefaultDriverOption.LOAD_BALANCING_POLICY_CLASS,
                        "DefaultLoadBalancingPolicy")
                .withString(
                        DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER,
                        cass.getDatacenter())
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(5))
                .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, 2)
                .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, 1)
                .build();

        CqlSessionBuilder builder = CqlSession.builder()
                .withConfigLoader(configLoader)
                .withLocalDatacenter(cass.getDatacenter())
                .withKeyspace(cass.getKeyspace());

        for (String host : cass.getContactPoints().split(",")) {
            builder.addContactPoint(new InetSocketAddress(host.trim(), cass.getPort()));
        }

        CqlSession session = builder.build();
        log.info("ScyllaDB CqlSession established for analytics service");
        return session;
    }
}
