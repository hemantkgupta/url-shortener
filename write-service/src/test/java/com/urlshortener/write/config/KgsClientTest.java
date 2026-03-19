package com.urlshortener.write.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.urlshortener.core.util.Base62Encoder;
import com.urlshortener.write.exception.KeyGenerationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KgsClientTest {

    private final WireMockServer wireMockServer = new WireMockServer(0);

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    @DisplayName("nextKey consumes a raw counter block and emits exact 8-character public keys")
    void nextKeyConsumesExclusiveRawCounterBlock() {
        wireMockServer.start();
        wireMockServer.stubFor(post(urlEqualTo("/internal/keys/next-block"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "startKey": 100,
                                  "endKey": 103,
                                  "blockSize": 3,
                                  "region": "local"
                                }
                                """)));

        KgsClient client = new KgsClient(propertiesForWireMock());

        String firstKey = client.nextKey();
        String secondKey = client.nextKey();
        String thirdKey = client.nextKey();

        assertThat(firstKey).isEqualTo(Base62Encoder.toShortKey(100));
        assertThat(secondKey).isEqualTo(Base62Encoder.toShortKey(101));
        assertThat(thirdKey).isEqualTo(Base62Encoder.toShortKey(102));
        assertThat(firstKey).hasSize(8).doesNotStartWith("0");
        assertThat(Base62Encoder.toShortKey(100)).isNotEqualTo(Base62Encoder.encode(100));
        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/internal/keys/next-block")));
    }

    @Test
    @DisplayName("nextKey wraps KGS failures in KeyGenerationException")
    void nextKeyWrapsKgsFailures() {
        wireMockServer.start();
        wireMockServer.stubFor(post(urlEqualTo("/internal/keys/next-block"))
                .willReturn(aResponse().withStatus(503)));

        KgsClient client = new KgsClient(propertiesForWireMock());

        assertThatThrownBy(client::nextKey)
                .isInstanceOf(KeyGenerationException.class)
                .hasMessageContaining("Failed to fetch key block from KGS");
    }

    private WriteServiceProperties propertiesForWireMock() {
        WriteServiceProperties properties = new WriteServiceProperties();
        properties.setKgsBaseUrl(wireMockServer.baseUrl());
        return properties;
    }
}
