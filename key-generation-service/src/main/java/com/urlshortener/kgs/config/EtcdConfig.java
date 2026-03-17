package com.urlshortener.kgs.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;

/**
 * Spring configuration that creates and manages the etcd client lifecycle.
 *
 * <p>A single {@link Client} instance is shared across the application; jetcd
 * is internally thread-safe and multiplexes multiple gRPC streams over one
 * connection.  The {@link KV} bean exposes the key-value operations used by
 * {@link com.urlshortener.kgs.service.BlockAllocator}.
 */
@Configuration
public class EtcdConfig {

    private static final Logger log = LoggerFactory.getLogger(EtcdConfig.class);

    private final KgsProperties kgsProperties;

    public EtcdConfig(KgsProperties kgsProperties) {
        this.kgsProperties = kgsProperties;
    }

    /**
     * Creates the jetcd {@link Client} using the endpoint(s) declared in
     * {@code kgs.etcd.endpoints}.  Multiple endpoints can be comma-separated;
     * jetcd will discover the leader automatically.
     */
    @Bean(destroyMethod = "close")
    public Client etcdClient() {
        String endpoints = kgsProperties.getEtcd().getEndpoints();
        log.info("Connecting to etcd at {}", endpoints);

        // jetcd accepts vararg URI strings; split on comma to support a list
        String[] uris = endpoints.split(",");
        return Client.builder()
                .endpoints(uris)
                .build();
    }

    /**
     * Exposes the {@link KV} (key-value) client derived from the shared
     * {@link Client}.  Callers should NOT close this bean directly; the parent
     * {@link Client} bean handles the lifecycle.
     */
    @Bean(destroyMethod = "close")
    public KV etcdKvClient(Client etcdClient) {
        return etcdClient.getKVClient();
    }
}
