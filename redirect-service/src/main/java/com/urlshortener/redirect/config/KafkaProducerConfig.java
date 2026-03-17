package com.urlshortener.redirect.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer configuration optimised for fire-and-forget click-event publishing.
 *
 * <p>The redirect service publishes click analytics to {@code click.events} with the
 * highest possible throughput and the lowest possible added latency.  Analytics can
 * tolerate occasional message loss (Flink will de-duplicate from other signals), so
 * we trade durability for speed:
 *
 * <ul>
 *   <li>{@code acks=0} — the producer does not wait for broker acknowledgement</li>
 *   <li>{@code retries=0} — no retry on transient broker errors; caller logs and moves on</li>
 *   <li>{@code batch.size=65536} (64 KB) — larger batches improve throughput at the cost of
 *       a small increase in buffering delay</li>
 *   <li>{@code linger.ms=5} — allows the producer to accumulate records for 5 ms before
 *       sending, improving batch fill rate under high concurrency</li>
 * </ul>
 */
@Configuration
public class KafkaProducerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerConfig.class);

    private final RedirectServiceProperties properties;

    public KafkaProducerConfig(RedirectServiceProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ProducerFactory<String, String> clickEventProducerFactory() {
        Map<String, Object> config = new HashMap<>();

        // Bootstrap servers from env var / application.yml
        String bootstrapServers = System.getenv().getOrDefault(
                "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Serialisation — both key (shortKey) and value (JSON) are strings
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Fire-and-forget: acks=0 means no wait for broker confirmation
        config.put(ProducerConfig.ACKS_CONFIG, "0");

        // No retries — analytics can absorb small loss; retries add latency on error paths
        config.put(ProducerConfig.RETRIES_CONFIG, 0);

        // 64 KB batch — good fill rate for high click traffic
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);

        // Linger 5 ms to let batch fill under burst traffic
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);

        // Compression reduces network bandwidth; snappy has low CPU overhead
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        log.info("Click-event Kafka producer configured — bootstrapServers={}, acks=0, "
                + "batchSize=65536, lingerMs=5", bootstrapServers);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(
            ProducerFactory<String, String> clickEventProducerFactory) {
        return new KafkaTemplate<>(clickEventProducerFactory);
    }
}
