package com.urlshortener.write.repository;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.urlshortener.core.domain.UrlMapping;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * ScyllaDB repository for {@link UrlMapping} and alias operations.
 *
 * <p>Uses the DataStax Java driver directly (no Spring Data Cassandra) for full
 * control over CQL, TTL, and prepared-statement lifecycle.  All statements are
 * prepared once at startup ({@link #prepareStatements()}) and then bound for
 * each request — this is the idiomatic high-performance driver pattern.
 *
 * <p>Driver exceptions are allowed to propagate to callers, which are responsible
 * for converting them to appropriate HTTP responses via the exception handler.
 */
@Repository
@DependsOn("schemaInitializer")
public class UrlMappingRepository {

    private static final Logger log = LoggerFactory.getLogger(UrlMappingRepository.class);
    private static final String KEYSPACE = "url_shortener";

    // ── CQL statements ────────────────────────────────────────────────────────

    private static final String INSERT_URL_MAPPING =
            "INSERT INTO " + KEYSPACE + ".url_mapping (short_key, long_url, user_id, created_at, expires_at, is_active) " +
            "VALUES (?, ?, ?, ?, ?, ?) USING TTL ?";

    private static final String SELECT_URL_MAPPING =
            "SELECT short_key, long_url, user_id, created_at, expires_at, is_active " +
            "FROM " + KEYSPACE + ".url_mapping WHERE short_key = ?";

    private static final String INSERT_ALIAS_MAPPING =
            "INSERT INTO " + KEYSPACE + ".alias_mapping (alias, short_key, user_id, created_at) " +
            "VALUES (?, ?, ?, ?)";

    private static final String INSERT_USER_URL_MAPPING =
            "INSERT INTO " + KEYSPACE + ".url_mapping_by_user (user_id, created_at, short_key, long_url, short_url, expires_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String EXISTS_ALIAS =
            "SELECT alias FROM " + KEYSPACE + ".alias_mapping WHERE alias = ? LIMIT 1";

    private static final String DELETE_URL_MAPPING =
            "DELETE FROM " + KEYSPACE + ".url_mapping WHERE short_key = ?";

    private static final String DELETE_ALIAS_MAPPING =
            "DELETE FROM " + KEYSPACE + ".alias_mapping WHERE alias = ?";

    private static final String DELETE_USER_URL_MAPPING =
            "DELETE FROM " + KEYSPACE + ".url_mapping_by_user WHERE user_id = ? AND created_at = ? AND short_key = ?";

    // ── Prepared statements (populated @PostConstruct) ────────────────────────

    private PreparedStatement insertUrlMappingPs;
    private PreparedStatement selectUrlMappingPs;
    private PreparedStatement insertAliasMappingPs;
    private PreparedStatement insertUserUrlMappingPs;
    private PreparedStatement existsAliasPs;
    private PreparedStatement deleteUrlMappingPs;
    private PreparedStatement deleteAliasMappingPs;
    private PreparedStatement deleteUserUrlMappingPs;

    private final CqlSession cqlSession;

    public UrlMappingRepository(CqlSession cqlSession) {
        this.cqlSession = cqlSession;
    }

    @PostConstruct
    void prepareStatements() {
        log.info("Preparing CQL statements for UrlMappingRepository");
        insertUrlMappingPs  = cqlSession.prepare(INSERT_URL_MAPPING);
        selectUrlMappingPs  = cqlSession.prepare(SELECT_URL_MAPPING);
        insertAliasMappingPs = cqlSession.prepare(INSERT_ALIAS_MAPPING);
        insertUserUrlMappingPs = cqlSession.prepare(INSERT_USER_URL_MAPPING);
        existsAliasPs       = cqlSession.prepare(EXISTS_ALIAS);
        deleteUrlMappingPs  = cqlSession.prepare(DELETE_URL_MAPPING);
        deleteAliasMappingPs = cqlSession.prepare(DELETE_ALIAS_MAPPING);
        deleteUserUrlMappingPs = cqlSession.prepare(DELETE_USER_URL_MAPPING);
        log.info("CQL statements prepared successfully");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Persists a {@link UrlMapping} to ScyllaDB with a row-level TTL.
     *
     * <p>The TTL is derived from {@link UrlMapping#getExpiresAt()} minus the current
     * instant.  If {@code expiresAt} is null (no expiry) a TTL of {@code 0} is used,
     * which means the row never expires (ScyllaDB semantics).
     *
     * @param mapping the mapping to persist; must not be null
     * @throws DriverException if ScyllaDB is unavailable or the write fails
     */
    public void save(UrlMapping mapping) {
        int ttlSeconds = computeTtlSeconds(mapping.getExpiresAt());

        BoundStatement bound = insertUrlMappingPs.bind(
                mapping.getShortKey(),
                mapping.getLongUrl(),
                mapping.getUserId(),      // nullable Long — driver handles null correctly
                mapping.getCreatedAt(),
                mapping.getExpiresAt(),   // nullable Instant
                mapping.isActive(),
                ttlSeconds);

        cqlSession.execute(bound);
        log.debug("Saved url_mapping: shortKey={}, ttlSeconds={}", mapping.getShortKey(), ttlSeconds);
    }

    /**
     * Retrieves a {@link UrlMapping} by its short key.
     *
     * @param shortKey the short key to look up
     * @return an {@link Optional} containing the mapping, or empty if not found
     * @throws DriverException if ScyllaDB is unavailable
     */
    public Optional<UrlMapping> findByShortKey(String shortKey) {
        BoundStatement bound = selectUrlMappingPs.bind(shortKey);
        Row row = cqlSession.execute(bound).one();

        if (row == null) {
            return Optional.empty();
        }

        UrlMapping mapping = UrlMapping.builder()
                .shortKey(row.getString("short_key"))
                .longUrl(row.getString("long_url"))
                .userId(row.isNull("user_id") ? null : row.getLong("user_id"))
                .createdAt(row.getInstant("created_at"))
                .expiresAt(row.isNull("expires_at") ? null : row.getInstant("expires_at"))
                .isActive(row.getBoolean("is_active"))
                .build();

        return Optional.of(mapping);
    }

    /**
     * Saves an alias-to-short-key mapping in the {@code alias_mapping} table.
     *
     * @param alias    the custom vanity alias
     * @param shortKey the generated or provided short key
     * @param userId   the owning user (may be null for anonymous)
     * @throws DriverException if ScyllaDB is unavailable
     */
    public void saveAlias(String alias, String shortKey, Long userId) {
        BoundStatement bound = insertAliasMappingPs.bind(
                alias,
                shortKey,
                userId,
                Instant.now());

        cqlSession.execute(bound);
        log.debug("Saved alias_mapping: alias={}, shortKey={}", alias, shortKey);
    }

    public void saveUserMapping(UrlMapping mapping, String shortUrl) {
        if (mapping.getUserId() == null) {
            return;
        }

        BoundStatement bound = insertUserUrlMappingPs.bind(
                mapping.getUserId(),
                mapping.getCreatedAt(),
                mapping.getShortKey(),
                mapping.getLongUrl(),
                shortUrl,
                mapping.getExpiresAt());

        cqlSession.execute(bound);
        log.debug("Saved url_mapping_by_user: userId={}, shortKey={}",
                mapping.getUserId(), mapping.getShortKey());
    }

    /**
     * Checks whether an alias already exists in the {@code alias_mapping} table.
     *
     * @param alias the alias to check
     * @return {@code true} if the alias is already in use
     * @throws DriverException if ScyllaDB is unavailable
     */
    public boolean existsAlias(String alias) {
        BoundStatement bound = existsAliasPs.bind(alias);
        return cqlSession.execute(bound).one() != null;
    }

    /**
     * Deletes a URL mapping by short key.
     *
     * @param shortKey the key to delete
     * @throws DriverException if ScyllaDB is unavailable
     */
    public void deleteByShortKey(String shortKey) {
        BoundStatement bound = deleteUrlMappingPs.bind(shortKey);
        cqlSession.execute(bound);
        log.debug("Deleted url_mapping: shortKey={}", shortKey);
    }

    public void deleteAlias(String alias) {
        BoundStatement bound = deleteAliasMappingPs.bind(alias);
        cqlSession.execute(bound);
        log.debug("Deleted alias_mapping: alias={}", alias);
    }

    public void deleteUserMapping(UrlMapping mapping) {
        if (mapping.getUserId() == null) {
            return;
        }

        BoundStatement bound = deleteUserUrlMappingPs.bind(
                mapping.getUserId(),
                mapping.getCreatedAt(),
                mapping.getShortKey());
        cqlSession.execute(bound);
        log.debug("Deleted url_mapping_by_user: userId={}, shortKey={}",
                mapping.getUserId(), mapping.getShortKey());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int computeTtlSeconds(Instant expiresAt) {
        if (expiresAt == null) {
            return 0; // 0 = no TTL in ScyllaDB
        }
        long seconds = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return (int) Math.max(1, seconds);
    }
}
