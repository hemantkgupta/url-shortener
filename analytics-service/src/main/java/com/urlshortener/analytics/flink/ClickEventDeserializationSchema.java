package com.urlshortener.analytics.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.urlshortener.core.domain.ClickEvent;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;

import java.io.IOException;

/**
 * Flink {@link DeserializationSchema} that deserializes raw Kafka message bytes
 * (UTF-8 JSON) into {@link ClickEvent} domain objects.
 *
 * <p><strong>Jackson shading note:</strong> Flink ships its own shaded Jackson in
 * {@code flink-shaded-jackson-*}.  To avoid classpath conflicts this schema carries
 * its own {@link ObjectMapper} instance, isolated from Spring's application-context
 * ObjectMapper.  The mapper is created in {@link #open} so it is not serialized
 * with the operator state.
 *
 * <p>Malformed JSON is handled by logging a warning and returning {@code null};
 * Flink filters nulls downstream via a subsequent {@code filter(e -> e != null)} step
 * added in {@link ClickEventFlinkJob#createPipeline}.
 */
public class ClickEventDeserializationSchema implements DeserializationSchema<ClickEvent> {

    private static final long serialVersionUID = 1L;

    // transient — recreated in open() on each task-manager, not serialized
    private transient ObjectMapper objectMapper;

    @Override
    public void open(InitializationContext context) throws Exception {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public ClickEvent deserialize(byte[] message) throws IOException {
        if (message == null || message.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(message, ClickEvent.class);
        } catch (IOException e) {
            // Log and swallow — bad messages should not crash the pipeline
            org.slf4j.LoggerFactory
                    .getLogger(ClickEventDeserializationSchema.class)
                    .warn("Failed to deserialize ClickEvent from Kafka message: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isEndOfStream(ClickEvent nextElement) {
        // Kafka source is unbounded
        return false;
    }

    @Override
    public TypeInformation<ClickEvent> getProducedType() {
        return TypeInformation.of(ClickEvent.class);
    }
}
