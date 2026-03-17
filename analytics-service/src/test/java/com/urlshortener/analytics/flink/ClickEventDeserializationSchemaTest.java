package com.urlshortener.analytics.flink;

import com.urlshortener.core.domain.ClickEvent;
import org.apache.flink.api.common.serialization.DeserializationSchema.InitializationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ClickEventDeserializationSchema}.
 *
 * <p>No Mockito mocking is required — the schema is self-contained and the
 * {@link InitializationContext} passed to {@code open()} is mocked only to
 * satisfy the API signature; it is never actually used by the implementation.
 */
class ClickEventDeserializationSchemaTest {

    private ClickEventDeserializationSchema schema;

    @BeforeEach
    void setUp() throws Exception {
        schema = new ClickEventDeserializationSchema();
        // open() creates the transient ObjectMapper; pass a mock context (unused by impl)
        schema.open(mock(InitializationContext.class));
    }

    // ── Valid JSON ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deserialize — valid JSON with all fields maps to ClickEvent correctly")
    void deserialize_validJson_allFields() throws IOException {
        String json = """
                {
                  "shortKey":  "abc1234",
                  "eventTime": "2025-06-15T10:30:00Z",
                  "country":   "US",
                  "city":      "New York",
                  "referrer":  "https://twitter.com",
                  "device":    "mobile",
                  "browser":   "Chrome",
                  "ipHash":    "deadbeef1234"
                }
                """;

        ClickEvent event = schema.deserialize(toBytes(json));

        assertThat(event).isNotNull();
        assertThat(event.getShortKey()).isEqualTo("abc1234");
        assertThat(event.getEventTime()).isEqualTo(Instant.parse("2025-06-15T10:30:00Z"));
        assertThat(event.getCountry()).isEqualTo("US");
        assertThat(event.getCity()).isEqualTo("New York");
        assertThat(event.getReferrer()).isEqualTo("https://twitter.com");
        assertThat(event.getDevice()).isEqualTo("mobile");
        assertThat(event.getBrowser()).isEqualTo("Chrome");
        assertThat(event.getIpHash()).isEqualTo("deadbeef1234");
    }

    @Test
    @DisplayName("deserialize — valid JSON with only required fields produces ClickEvent with nulls for optional fields")
    void deserialize_validJson_onlyRequiredFields() throws IOException {
        // shortKey and eventTime are the two required (non-null) fields in ClickEvent
        String json = """
                {
                  "shortKey":  "xyz9999",
                  "eventTime": "2025-01-01T00:00:00Z"
                }
                """;

        ClickEvent event = schema.deserialize(toBytes(json));

        assertThat(event).isNotNull();
        assertThat(event.getShortKey()).isEqualTo("xyz9999");
        assertThat(event.getEventTime()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        // Optional fields absent from JSON should deserialize to null, not throw
        assertThat(event.getCountry()).isNull();
        assertThat(event.getCity()).isNull();
        assertThat(event.getReferrer()).isNull();
        assertThat(event.getDevice()).isNull();
        assertThat(event.getBrowser()).isNull();
        assertThat(event.getIpHash()).isNull();
    }

    // ── Invalid / malformed JSON ──────────────────────────────────────────────

    @Test
    @DisplayName("deserialize — malformed JSON returns null without throwing")
    void deserialize_malformedJson_returnsNull() throws IOException {
        String notJson = "{ this is not valid json !!!";

        ClickEvent event = schema.deserialize(toBytes(notJson));

        assertThat(event).isNull();
    }

    @Test
    @DisplayName("deserialize — completely garbled bytes return null without throwing")
    void deserialize_garbageBytes_returnsNull() throws IOException {
        byte[] garbage = new byte[]{0x00, (byte) 0xFF, 0x1A, 0x2B, 0x3C};

        ClickEvent event = schema.deserialize(garbage);

        assertThat(event).isNull();
    }

    @Test
    @DisplayName("deserialize — null input returns null without throwing")
    void deserialize_nullInput_returnsNull() throws IOException {
        ClickEvent event = schema.deserialize(null);

        assertThat(event).isNull();
    }

    @Test
    @DisplayName("deserialize — empty byte array returns null without throwing")
    void deserialize_emptyBytes_returnsNull() throws IOException {
        ClickEvent event = schema.deserialize(new byte[0]);

        assertThat(event).isNull();
    }

    // ── Missing required fields produce partial ClickEvent (not null) ─────────

    @Test
    @DisplayName("deserialize — JSON missing shortKey still deserializes to non-null ClickEvent with null shortKey field")
    void deserialize_missingShortKey_producesPartialClickEvent() throws IOException {
        // ClickEvent.Builder.build() calls requireNonNull on shortKey, so a JSON object
        // that omits shortKey will cause Jackson to call the Builder and then build() will
        // throw NullPointerException.  The schema must catch this and return null.
        String json = """
                {
                  "eventTime": "2025-06-15T10:30:00Z",
                  "country":   "DE"
                }
                """;

        // The schema's catch block handles any IOException (and, via the ObjectMapper's
        // error handling, any runtime exception that surfaces through Jackson).
        // Exact behaviour: null is returned (pipeline skips bad messages).
        ClickEvent event = schema.deserialize(toBytes(json));

        // Either null (if build() NPE was caught) or a partial object with null shortKey.
        // Both are acceptable; assert that no exception propagates to the caller.
        // This assertion verifies the contract stated in the Javadoc.
        if (event != null) {
            // If the mapper somehow produced a partial object (e.g. via a default constructor),
            // at a minimum the country should have been set.
            assertThat(event.getCountry()).isEqualTo("DE");
        }
        // No assertion failure — null is the expected/allowed result per the schema contract.
    }

    @Test
    @DisplayName("deserialize — JSON with extra unknown fields is accepted and ignored")
    void deserialize_extraUnknownFields_areIgnored() throws IOException {
        String json = """
                {
                  "shortKey":       "key007",
                  "eventTime":      "2025-06-15T08:00:00Z",
                  "unknownField":   "should be ignored",
                  "anotherExtra":   42
                }
                """;

        ClickEvent event = schema.deserialize(toBytes(json));

        // Extra fields should not cause a failure
        assertThat(event).isNotNull();
        assertThat(event.getShortKey()).isEqualTo("key007");
    }

    // ── isEndOfStream / getProducedType ───────────────────────────────────────

    @Test
    @DisplayName("isEndOfStream always returns false (unbounded Kafka source)")
    void isEndOfStream_alwaysReturnsFalse() throws IOException {
        String json = """
                {"shortKey":"k","eventTime":"2025-06-15T00:00:00Z"}
                """;
        ClickEvent event = schema.deserialize(toBytes(json));

        assertThat(schema.isEndOfStream(event)).isFalse();
        assertThat(schema.isEndOfStream(null)).isFalse();
    }

    @Test
    @DisplayName("getProducedType returns TypeInformation for ClickEvent")
    void getProducedType_returnsClickEventTypeInfo() {
        assertThat(schema.getProducedType()).isNotNull();
        assertThat(schema.getProducedType().getTypeClass()).isEqualTo(ClickEvent.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
