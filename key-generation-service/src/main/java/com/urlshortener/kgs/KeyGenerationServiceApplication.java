package com.urlshortener.kgs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.urlshortener.kgs.config.KgsProperties;

/**
 * Entry point for the Key Generation Service (KGS).
 *
 * <p>KGS is responsible for allocating globally unique, monotonically
 * increasing counter blocks from etcd and exposing them to write-service pods
 * via an internal HTTP API.  Virtual threads (Project Loom) are enabled so that
 * every incoming request is handled by a lightweight carrier thread, giving
 * high throughput with minimal heap overhead.
 */
@SpringBootApplication
@EnableConfigurationProperties(KgsProperties.class)
public class KeyGenerationServiceApplication {

    private static final Logger log =
            LoggerFactory.getLogger(KeyGenerationServiceApplication.class);

    public static void main(String[] args) {
        var context = SpringApplication.run(KeyGenerationServiceApplication.class, args);
        var props = context.getBean(KgsProperties.class);
        log.info(
                "Key Generation Service started — region={}, blockSize={}, etcd={}",
                props.getRegion(),
                props.getBlockSize(),
                props.getEtcd().getEndpoints());
    }
}
