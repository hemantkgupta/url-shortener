package com.urlshortener.redirect.repository;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.urlshortener.core.domain.UrlMapping;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Read-only repository for {@link UrlMapping} backed by ScyllaDB / Cassandra.
 *
 * <p>All prepared statements are compiled once in {@link #prepareStatements()} and
 * then reused across requests, avoiding repeated CQL parsing overhead on the hot path.
 *
 * <p>This repository is intentionally read-only.  All writes go through the
 * Write Service which owns the authoritative ScyllaDB schema.
 *
 * <p>The underlying table schema (created by Write Service's {@code SchemaInitializer}):
 * <pre>{@code
 * CREATE TABLE url_shortener.url_mappings (
 *     short_key  TEXT PRIMARY KEY,
 *     long_url   TEXT,
 *     user_id    BIGINT,
 *     created_at TIMESTAMP,
 *     expires_at TIMESTAMP,
 *     is_active  BOOLEAN
 * );
 * }</pre>
 */
@Repository
public class UrlMappingRepository {

    private static final Logger log = LoggerFactory.getLogger(UrlMappingRepository.class);

    // CQL column names matching the schema created by Write Service
    private static final String COL_SHORT_KEY  = "short_key";
    private static final String COL_LONG_URL   = "long_url";
    private static final String COL_USER_ID    = "user_id";
    private static final String COL_CREATED_AT = "created_at";
    private static final String COL_EXPIRES_AT = "expires_at";
    private static final String COL_IS_ACTIVE  = "is_active";

    private final CqlSession cqlSession;

    // Prepared statements — compiled once, reused for every request
    private PreparedStatement findByShortKeyStmt;
    private PreparedStatement existsByShortKeyStmt;

    public UrlMappingRepository(CqlSession cqlSession) {
        this.cqlSession = cqlSession;
    }

    /**
     * Pre-compiles all CQL prepared statements.
     *
     * <p>Called automatically by Spring after dependency injection.
     * Using {@code @PostConstruct} ensures statements are ready before the first request.
     */
    @PostConstruct
    public void prepareStatements() {
        log.info("Preparing CQL statements for UrlMappingRepository");

        findByShortKeyStmt = cqlSession.prepare(
                "SELECT short_key, long_url, user_id, created_at, expires_at, is_active "
                        + "FROM url_mappings WHERE short_key = ?");

        existsByShortKeyStmt = cqlSession.prepare(
                "SELECT short_key FROM url_mappings WHERE short_key = ?");

        log.info("CQL prepared statements compiled successfully");
    }

    /**
     * Retrieves the full {@link UrlMapping} for the given short key.
     *
     * <p>Returns {@link Optional#empty()} when no row exists.  The caller is responsible
     * for checking {@link UrlMapping#isActive()} and {@link UrlMapping#isExpired(Instant)}.
     *
     * @param shortKey the 1–8 character Base-62 short key
     * @return an {@link Optional} containing the mapping, or empty if not found
     */
    public Optional<UrlMapping> findByShortKey(String shortKey) {
        ResultSet resultSet = cqlSession.execute(
                findByShortKeyStmt.bind(shortKey));

        Row row = resultSet.one();
        if (row == null) {
            return Optional.empty();
        }

        UrlMapping mapping = UrlMapping.builder()
                .shortKey(row.getString(COL_SHORT_KEY))
                .longUrl(row.getString(COL_LONG_URL))
                .userId(row.isNull(COL_USER_ID) ? null : row.getLong(COL_USER_ID))
                .createdAt(row.getInstant(COL_CREATED_AT))
                .expiresAt(row.isNull(COL_EXPIRES_AT) ? null : row.getInstant(COL_EXPIRES_AT))
                .isActive(row.getBoolean(COL_IS_ACTIVE))
                .build();

        return Optional.of(mapping);
    }

    /**
     * Fast existence check for a short key — fetches only the key column to minimise
     * read bandwidth.
     *
     * <p>Prefer {@link #findByShortKey(String)} when the full mapping is needed;
     * use this method only when a boolean check suffices.
     *
     * @param shortKey the 1–8 character Base-62 short key
     * @return {@code true} if a row with this short key exists in ScyllaDB
     */
    public boolean existsByShortKey(String shortKey) {
        ResultSet resultSet = cqlSession.execute(
                existsByShortKeyStmt.bind(shortKey));
        return resultSet.one() != null;
    }
}
