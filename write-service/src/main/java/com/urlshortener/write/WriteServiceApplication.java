package com.urlshortener.write;

import com.urlshortener.write.config.WriteServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Write Service.
 *
 * <p>The Write Service owns the URL-creation path:
 * <ol>
 *   <li>Validate the long URL (format, length, circular-redirect, safe-browsing stub)</li>
 *   <li>Generate or validate a short key via KGS</li>
 *   <li>Persist to ScyllaDB</li>
 *   <li>Asynchronously pre-warm Redis and populate the Bloom filter</li>
 *   <li>Publish a {@code url.created} Kafka event for downstream consumers</li>
 * </ol>
 *
 * <p>Virtual threads (Project Loom) are enabled via {@code spring.threads.virtual.enabled=true}
 * in {@code application.yml}, which causes Spring MVC to use a virtual-thread executor for
 * request handling automatically (Spring Boot 3.2+).
 */
@SpringBootApplication
@EnableConfigurationProperties(WriteServiceProperties.class)
public class WriteServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(WriteServiceApplication.class);

    public static void main(String[] args) {
        var context = SpringApplication.run(WriteServiceApplication.class, args);
        var props = context.getBean(WriteServiceProperties.class);
        log.info(
                "Write Service started — kgsBaseUrl={}, ownDomain={}, kafkaTopic={}",
                props.getKgsBaseUrl(),
                props.getOwnDomain(),
                props.getKafka().getTopic().getUrlCreated());
    }
}
