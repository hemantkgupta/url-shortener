package com.urlshortener.write;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Full-stack integration test for the Write Service.
 *
 * <p>Infrastructure containers:
 * <ul>
 *   <li>{@link CassandraContainer} — ScyllaDB-compatible Cassandra for persistence</li>
 *   <li>{@link RedisContainer} — Redis for cache warm-up and Bloom filter</li>
 *   <li>{@link KafkaContainer} — Kafka broker for event publishing</li>
 *   <li>{@link WireMockServer} — stubs the KGS {@code /internal/keys/next-block} endpoint</li>
 * </ul>
 *
 * <p>The full Spring context is started with random port; all infrastructure
 * coordinates are injected via {@link DynamicPropertySource}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WriteServiceIntegrationTest {

    private static final String OWNER_TOKEN = "integration-owner-token";
    private static final String OWNER_SUBJECT = "google-user-123";

    // ── Containers ────────────────────────────────────────────────────────────

    @Container
    static final CassandraContainer<?> cassandra =
            new CassandraContainer<>(DockerImageName.parse("cassandra:4.1"))
                    .withInitScript("db/schema.cql");

    @Container
    static final RedisContainer redis =
            new RedisContainer(DockerImageName.parse("redis:7.2-alpine"));

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    // WireMock is managed manually (not via @Container) for fine-grained stub control
    static WireMockServer wireMock;

    // ── Spring context wiring ─────────────────────────────────────────────────

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @MockBean
    JwtDecoder jwtDecoder;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        stubKgsNextBlock(1L, 1000L);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null && wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // Cassandra
        registry.add("write.cassandra.contact-points", cassandra::getHost);
        registry.add("write.cassandra.port",
                () -> cassandra.getMappedPort(9042).toString());
        registry.add("write.cassandra.keyspace",  () -> "url_shortener");
        registry.add("write.cassandra.datacenter", () -> "datacenter1");

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379).toString());

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("write.kafka.bootstrapServers",    kafka::getBootstrapServers);

        // KGS — point at WireMock
        registry.add("write.kgs-base-url",
                () -> "http://localhost:" + wireMock.port());

        // Disable Safe Browsing for tests
        registry.add("write.safe-browsing.enabled", () -> "false");

        // Use localhost as own-domain
        registry.add("write.own-domain", () -> "localhost");
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /v1/urls returns 201 Created with Location header and body")
    void postUrlReturns201WithLocationHeader() {
        String requestBody = """
                {
                    "longUrl": "https://example.com/some/very/long/path?utm_source=test"
                }
                """;

        ResponseEntity<Map> response = restTemplate.exchange(
                RequestEntity.post(URI.create("/v1/urls"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();

        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("shortKey"))
                .isNotNull()
                .asString()
                .matches("[0-9A-Za-z]{8}")
                .doesNotStartWith("0");
        assertThat(body.get("shortUrl")).isNotNull().asString().contains((String) body.get("shortKey"));
        assertThat(body.get("longUrl")).isNotNull();
        assertThat(body.get("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("POST /v1/urls with customKey creates alias and returns 201")
    void postUrlWithCustomKeyCreatesAlias() {
        String alias = "my-test-brand";
        String requestBody = String.format("""
                {
                    "longUrl": "https://example.com/branded-page",
                    "customKey": "%s"
                }
                """, alias);

        ResponseEntity<Map> response = restTemplate.exchange(
                RequestEntity.post(URI.create("/v1/urls"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("shortKey")).isEqualTo(alias);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString()).contains(alias);
    }

    @Test
    @DisplayName("POST /v1/urls with duplicate customKey returns 409 Conflict")
    void postUrlWithDuplicateCustomKeyReturns409() {
        String alias = "unique-alias-for-conflict-test";

        // First request — should succeed
        String requestBody = String.format("""
                {
                    "longUrl": "https://first.example.com/page",
                    "customKey": "%s"
                }
                """, alias);

        ResponseEntity<Map> firstResponse = restTemplate.exchange(
                RequestEntity.post(URI.create("/v1/urls"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody),
                Map.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second request with same alias — should conflict
        String conflictBody = String.format("""
                {
                    "longUrl": "https://second.example.com/other",
                    "customKey": "%s"
                }
                """, alias);

        ResponseEntity<Map> conflictResponse = restTemplate.exchange(
                RequestEntity.post(URI.create("/v1/urls"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(conflictBody),
                Map.class);

        assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<?, ?> errorBody = conflictResponse.getBody();
        assertThat(errorBody).isNotNull();
        assertThat(errorBody.get("title")).isEqualTo("Alias Already Taken");
    }

    @Test
    @DisplayName("POST /v1/urls with invalid URL returns 400 Bad Request")
    void postUrlWithInvalidUrlReturns400() {
        String requestBody = """
                {
                    "longUrl": "not-a-valid-url"
                }
                """;

        ResponseEntity<Map> response = restTemplate.exchange(
                RequestEntity.post(URI.create("/v1/urls"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /v1/urls with missing longUrl returns 400 Bad Request")
    void postUrlWithMissingLongUrlReturns400() {
        String requestBody = "{}";

        ResponseEntity<Map> response = restTemplate.exchange(
                RequestEntity.post(URI.create("/v1/urls"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("DELETE /v1/urls/{shortKey} returns 204 No Content for the owning user")
    void deleteShortUrlReturns204() {
        when(jwtDecoder.decode(OWNER_TOKEN)).thenReturn(ownerJwt());

        // First create a URL as the authenticated owner
        String createBody = """
                {
                    "longUrl": "https://example.com/to-be-deleted"
                }
                """;

        ResponseEntity<Map<String, Object>> created = restTemplate.exchange(
                RequestEntity.post(URI.create("/v1/urls"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + OWNER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(createBody),
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String shortKey = (String) created.getBody().get("shortKey");

        // Now delete it with the same identity
        ResponseEntity<Void> deleted = restTemplate.exchange(
                RequestEntity.delete(URI.create("/v1/urls/" + shortKey))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + OWNER_TOKEN)
                        .build(),
                Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── WireMock stub helpers ─────────────────────────────────────────────────

    private static void stubKgsNextBlock(long startKey, long endKey) {
        String responseBody = String.format("""
                {
                    "startKey": %d,
                    "endKey": %d,
                    "blockSize": %d,
                    "region": "us-east-1"
                }
                """, startKey, endKey, (endKey - startKey));

        wireMock.stubFor(
                post(urlEqualTo("/internal/keys/next-block"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(responseBody)));
    }

    private static Jwt ownerJwt() {
        return Jwt.withTokenValue(OWNER_TOKEN)
                .header("alg", "RS256")
                .claim("sub", OWNER_SUBJECT)
                .claim("email", "owner@example.com")
                .claim("name", "Integration Owner")
                .claim("aud", "integration-test-client")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
