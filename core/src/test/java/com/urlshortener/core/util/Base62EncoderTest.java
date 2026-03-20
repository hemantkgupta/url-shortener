package com.urlshortener.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class Base62EncoderTest {

    @Test
    @DisplayName("toShortKey returns exact 8-character public keys without leading zeros")
    void toShortKeyReturnsEightCharactersWithoutLeadingZeros() {
        assertThat(Base62Encoder.toShortKey(0))
                .hasSize(8)
                .matches("[0-9A-Za-z]{8}")
                .doesNotStartWith("0");

        assertThat(Base62Encoder.toShortKey(1))
                .hasSize(8)
                .doesNotStartWith("0");
    }

    @Test
    @DisplayName("toShortKey is unique for a sample of adjacent counters")
    void toShortKeyIsUniqueForAdjacentCounters() {
        Set<String> keys = new HashSet<>();
        for (long counter = 0; counter < 1_000; counter++) {
            keys.add(Base62Encoder.toShortKey(counter));
        }

        assertThat(keys).hasSize(1_000);
    }

    @Test
    @DisplayName("toShortKey scatters early counters across visible prefixes")
    void toShortKeyScattersEarlyCountersAcrossPrefixes() {
        Set<String> prefixes = new HashSet<>();
        for (long counter = 0; counter < 64; counter++) {
            prefixes.add(Base62Encoder.toShortKey(counter).substring(0, 4));
        }

        assertThat(prefixes).hasSizeGreaterThan(8);
    }

    @Test
    @DisplayName("encode of the 8-character floor starts at 10000000")
    void encodeFloorStartsAtEightCharacterRange() {
        assertThat(Base62Encoder.encode(Base62Encoder.minFixedLengthValue(Base62Encoder.KEY_LENGTH)))
                .isEqualTo("10000000");
    }
}
