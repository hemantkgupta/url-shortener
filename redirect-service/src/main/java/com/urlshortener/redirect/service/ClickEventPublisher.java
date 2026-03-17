package com.urlshortener.redirect.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.core.domain.ClickEvent;
import com.urlshortener.redirect.config.RedirectServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Asynchronous publisher of {@link ClickEvent} records to the {@code click.events}
 * Kafka topic.
 *
 * <p>Publishing runs inside a virtual thread (via {@link Thread#ofVirtual()}) so it
 * never blocks the carrier thread handling the HTTP request.  The Kafka producer is
 * configured with {@code acks=0} (fire-and-forget), so the call to
 * {@link KafkaTemplate#send} itself returns almost immediately.
 *
 * <h2>Error handling</h2>
 * JSON serialisation failures and Kafka send errors are logged as warnings and
 * swallowed.  Click event loss is acceptable for analytics; we must not degrade
 * redirect latency or availability due to analytics pipeline issues.
 */
@Component
public class ClickEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClickEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String clickEventsTopic;

    public ClickEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            RedirectServiceProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.clickEventsTopic = properties.getKafka().getTopic().getClickEvents();
    }

    /**
     * Serialises the {@link ClickEvent} to JSON and publishes it to Kafka on a
     * virtual thread.
     *
     * <p>The method returns immediately; the actual publish happens asynchronously.
     * Callers must not assume the event has been sent when this method returns.
     *
     * @param event the click event to publish; must not be {@code null}
     */
    public void publish(ClickEvent event) {
        // Capture the serialised form on the calling thread to avoid races if the
        // event object were mutable (it isn't, but this is defensive).
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialise ClickEvent for shortKey={}: {}",
                    event.getShortKey(), ex.getMessage());
            return;
        }

        String shortKey = event.getShortKey();

        // Virtual thread — non-blocking from the carrier thread's perspective
        Thread.ofVirtual()
                .name("click-event-publisher-" + shortKey)
                .start(() -> {
                    try {
                        kafkaTemplate.send(clickEventsTopic, shortKey, json)
                                .whenComplete((result, ex) -> {
                                    if (ex != null) {
                                        log.warn("Click event publish failed for shortKey={}: {}",
                                                shortKey, ex.getMessage());
                                    } else {
                                        log.debug("Click event published for shortKey={} offset={}",
                                                shortKey,
                                                result.getRecordMetadata().offset());
                                    }
                                });
                    } catch (Exception ex) {
                        log.warn("Unexpected error publishing click event for shortKey={}: {}",
                                shortKey, ex.getMessage());
                    }
                });
    }
}
