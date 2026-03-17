package com.urlshortener.redirect.config;

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
 * Spring {@link Configuration} that creates the {@link CqlSession} bean used by
 * all Cassandra / ScyllaDB repositories in the Redirect Service.
 *
 * <p>The session is configured with:
 * <ul>
 *   <li>DC-aware load balancing ({@code DefaultLoadBalancingPolicy}) with token-aware routing
 *       to minimise coordinator hops on the hot path</li>
 *   <li>A tight request timeout (5 s) that surfaces latency problems early</li>
 *   <li>Conservative connection pool sizes appropriate for virtual-thread workloads</li>
 * </ul>
 *
 * <p>Keyspace creation is handled by the Write Service's {@code SchemaInitializer}.
 * This service only reads; it does not run DDL.
 */
@Configuration
public class CassandraConfig {

    private static final Logger log = LoggerFactory.getLogger(CassandraConfig.class);

    private final RedirectServiceProperties properties;

    public CassandraConfig(RedirectServiceProperties properties) {
        this.properties = properties;
    }

    @Bean
    public CqlSession cqlSession() {
        RedirectServiceProperties.Cassandra cass = properties.getCassandra();

        log.info(
                "Connecting to ScyllaDB — contactPoints={}, port={}, keyspace={}, dc={}",
                cass.getContactPoints(), cass.getPort(), cass.getKeyspace(), cass.getDatacenter());

        DriverConfigLoader configLoader = DriverConfigLoader.programmaticBuilder()
                // Token-aware policy with DC-aware round-robin as the child policy
                .withString(
                        DefaultDriverOption.LOAD_BALANCING_POLICY_CLASS,
                        "DefaultLoadBalancingPolicy")
                .withString(
                        DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER,
                        cass.getDatacenter())
                // Request timeout — tight on the hot path; cache misses are rare
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(5))
                // Conservative pool: virtual threads avoid idle thread waste
                .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, 2)
                .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, 1)
                .build();

        CqlSessionBuilder builder = CqlSession.builder()
                .withConfigLoader(configLoader)
                .withLocalDatacenter(cass.getDatacenter())
                .withKeyspace(cass.getKeyspace());

        // Support comma-separated contact points for multi-node clusters
        for (String host : cass.getContactPoints().split(",")) {
            builder.addContactPoint(new InetSocketAddress(host.trim(), cass.getPort()));
        }

        CqlSession session = builder.build();
        log.info("ScyllaDB CqlSession established successfully");
        return session;
    }
}
