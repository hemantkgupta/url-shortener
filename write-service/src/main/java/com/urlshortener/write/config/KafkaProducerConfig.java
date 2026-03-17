package com.urlshortener.write.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer configuration for the Write Service.
 *
 * <p>The producer is configured for at-least-once delivery with idempotence:
 * <ul>
 *   <li>{@code acks=all} — wait for all in-sync replica acknowledgements</li>
 *   <li>{@code retries=3} — retry transient failures up to 3 times</li>
 *   <li>{@code enable.idempotence=true} — exactly-once semantics at the producer level</li>
 * </ul>
 *
 * <p>{@code KafkaTemplate.send()} returns a {@code CompletableFuture} and is therefore
 * non-blocking from the caller's perspective.
 */
@Configuration
public class KafkaProducerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerConfig.class);

    @Value("${write.kafka.bootstrapServers:${spring.kafka.bootstrap-servers:localhost:9092}}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> kafkaProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // MAX_IN_FLIGHT must be <= 5 when idempotence is enabled
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        log.info("Kafka producer configured — bootstrapServers={}", bootstrapServers);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(
            ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
