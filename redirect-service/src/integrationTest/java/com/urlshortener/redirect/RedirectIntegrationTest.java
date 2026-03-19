package com.urlshortener.redirect;

import com.datastax.oss.driver.api.core.CqlSession;
import com.redis.testcontainers.RedisStackContainer;
import com.urlshortener.redirect.service.BloomFilterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Spring Boot integration test for the redirect hot path.
 *
 * <h2>Infrastructure</h2>
 * <ul>
 *   <li><strong>Redis</strong> — {@code RedisStackContainer} (redis/redis-stack) includes
 *       the RedisBloom module required by {@link BloomFilterService}.</li>
 *   <li><strong>ScyllaDB</strong> — approximated by the Testcontainers Cassandra module
 *       ({@code scylladb/scylla:6.1}).  The CQL wire protocol is compatible.</li>
 *   <li><strong>Kafka</strong> — replaced by a {@link MockBean} {@link KafkaTemplate} so no
 *       real broker is needed; click events are fire-and-forget analytics and may be dropped.</li>
 * </ul>
 *
 * <h2>Bloom filter strategy</h2>
 * {@link BloomFilterService} is mocked via {@link MockBean} and configured to return
 * {@code true} (key may exist) for the seeded short keys in each test, and {@code false}
 * for the unknown-key test.  This avoids the need to pre-seed the RedisBloom filter via
 * {@code BF.ADD}, which simplifies test setup while still exercising every other layer.
 *
 * <h2>Thread safety</h2>
 * Containers are declared {@code static} so they are started once and shared across all
 * tests in this class, saving significant startup time.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RedirectIntegrationTest {

    // ── Containers ────────────────────────────────────────────────────────────

    /**
     * Redis Stack includes RedisBloom and RedisJSON modules needed by the service.
     * Shared across all tests (static) to avoid repeated container restarts.
     */
    @Container
    static final RedisStackContainer REDIS =
            new RedisStackContainer(RedisStackContainer.DEFAULT_IMAGE_NAME
                    .withTag(RedisStackContainer.DEFAULT_TAG));

    /**
     * ScyllaDB via Testcontainers Cassandra module (CQL-compatible).
     * The image is overridden to scylladb/scylla:6.1 to match production.
     */
    @Container
    static final CassandraContainer SCYLLA =
            new CassandraContainer(
                    DockerImageName.parse("scylladb/scylla:6.1")
                            .asCompatibleSubstituteFor("cassandra"))
                    .withInitScript("db/redirect-schema.cql")
                    .withStartupTimeout(Duration.ofMinutes(3));

    // ── Property wiring ───────────────────────────────────────────────────────

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // Redis
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // Redisson (reads REDIS_HOST / REDIS_PORT env vars in RedisConfig; wire via properties
        // by setting system properties before context loads, handled via the TestRedisConfig bean)

        // ScyllaDB / Cassandra
        registry.add("redirect.cassandra.contact-points", SCYLLA::getHost);
        registry.add("redirect.cassandra.port", () -> SCYLLA.getMappedPort(9042));
        registry.add("redirect.cassandra.keyspace", () -> "url_shortener");
        registry.add("redirect.cassandra.datacenter", () -> "datacenter1");

        // Kafka is mocked — point at a non-existent address to ensure it is never contacted
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9099");
    }

    // ── Mocks ─────────────────────────────────────────────────────────────────

    /**
     * KafkaTemplate mock: prevents any real Kafka producer from being created.
     * {@link @MockBean} replaces the auto-configured bean in the test application context.
     */
    @MockBean
    @SuppressWarnings("rawtypes")
    KafkaTemplate kafkaTemplate;

    /**
     * BloomFilterService mock: controls fast-path gate results per test.
     */
    @MockBean
    BloomFilterService bloomFilterService;

    // ── Spring beans ──────────────────────────────────────────────────────────

    @Autowired
    TestRestTemplate restTemplate;

    /** Used directly to seed Redis cache entries in tests. */
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /** Used directly to seed ScyllaDB rows in tests. */
    @Autowired
    CqlSession cqlSession;

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String SHORT_KEY   = "abc123";
    private static final String LONG_URL    = "https://www.example.com/integration-test";
    private static final String CACHE_KEY   = "url:" + SHORT_KEY;

    // ── Schema & seed setup ───────────────────────────────────────────────────

    /**
     * Clears any leftover state from previous tests so each test is hermetic.
     */
    @BeforeEach
    void setUpSchemaAndClean() {
        // Wipe any rows seeded by previous tests
        cqlSession.execute("TRUNCATE url_mapping");

        // Wipe cache keys seeded by previous tests
        stringRedisTemplate.delete(CACHE_KEY);

        // Default: bloom filter rejects all keys → each test opt-in to allow specific keys
        when(bloomFilterService.exists(anyString())).thenReturn(false);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("redirect_existingKey_returns302: active DB row + cache seeded → GET /{key} returns 302 with Location header")
    void redirect_existingKey_returns302() {
        // Seed ScyllaDB with an active, non-expired mapping
        cqlSession.execute(
                "INSERT INTO url_mapping "
                + "(short_key, long_url, user_id, created_at, is_active) "
                + "VALUES ('" + SHORT_KEY + "', '" + LONG_URL + "', null, toTimestamp(now()), true)");

        // Seed Redis cache so the service takes the fast cache path
        stringRedisTemplate.opsForValue().set(CACHE_KEY, LONG_URL, Duration.ofHours(24));

        // Bloom filter allows this key
        when(bloomFilterService.exists(SHORT_KEY)).thenReturn(true);

        ResponseEntity<Void> response = restTemplate.getForEntity("/" + SHORT_KEY, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo(LONG_URL);
    }

    @Test
    @DisplayName("redirect_unknownKey_returns404: bloom filter rejects key → GET /nonexistent123 returns 404 immediately")
    void redirect_unknownKey_returns404() {
        // bloomFilterService already configured to return false for all keys (see @BeforeEach)
        // No DB or cache seeding needed — the Bloom gate short-circuits everything

        ResponseEntity<Void> response = restTemplate.getForEntity("/nokey99", Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("redirect_cachedKey_returns302FromCache: only Redis seeded (no DB row) → GET /{key} returns 302 from cache alone")
    void redirect_cachedKey_returns302FromCache() {
        // Seed only the Redis cache — deliberately no DB row to prove the cache path is exercised
        stringRedisTemplate.opsForValue().set(CACHE_KEY, LONG_URL, Duration.ofHours(24));

        // Bloom filter allows this key
        when(bloomFilterService.exists(SHORT_KEY)).thenReturn(true);

        ResponseEntity<Void> response = restTemplate.getForEntity("/" + SHORT_KEY, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo(LONG_URL);
    }

    // ── Test-specific Spring configuration ────────────────────────────────────

    /**
     * Overrides the Redisson client bean to point at the Testcontainers Redis host/port.
     *
     * <p>{@link com.urlshortener.redirect.config.RedisConfig#redissonClient()} reads
     * {@code REDIS_HOST} / {@code REDIS_PORT} from environment variables, which cannot be
     * set dynamically in tests.  This {@link Primary} bean takes precedence and wires the
     * correct container coordinates instead.
     *
     * <p>The Redisson client is used exclusively by {@link BloomFilterService}, which is
     * itself a {@link MockBean} in these tests, so the Redisson client is never actually
     * called.  We still provide a valid configuration bean to satisfy the Spring context.
     */
    @TestConfiguration
    static class TestRedissonConfig {

        @Bean
        @Primary
        org.redisson.api.RedissonClient testRedissonClient() {
            org.redisson.config.Config config = new org.redisson.config.Config();
            config.useSingleServer()
                    .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
                    .setConnectionMinimumIdleSize(1)
                    .setConnectionPoolSize(2)
                    .setConnectTimeout(2000)
                    .setTimeout(2000);
            return org.redisson.Redisson.create(config);
        }
    }
}
