package com.urlshortener.analytics.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.urlshortener.core.dto.ShortenResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service that reads the {@code url_mapping_by_user} table from
 * ScyllaDB / Cassandra to return a paginated list of shortened URLs owned by a user.
 *
 * <h2>Schema assumptions</h2>
 * The per-user lookup table {@code url_mapping_by_user} is assumed to have:
 * <pre>
 * CREATE TABLE url_mapping_by_user (
 *     user_id BIGINT,
 *     created_at TIMESTAMP,
 *     short_key TEXT,
 *     long_url TEXT,
 *     short_url TEXT,
 *     expires_at TIMESTAMP,
 *     PRIMARY KEY (user_id, created_at, short_key)
 *     WITH CLUSTERING ORDER BY (created_at DESC, short_key ASC);
 * </pre>
 *
 * <h2>Pagination</h2>
 * Cassandra's native paging is used via
 * {@link BoundStatement#setPageSize(int)} and {@link ResultSet#getExecutionInfo()}.
 * Only the requested {@code page * size} rows are fetched per call — a simple
 * offset-based approach suitable for the analytics UI.
 *
 * <p>For production-scale cursor-based pagination, replace with
 * {@code PagingState}-based continuation tokens.
 */
@Service
public class LinkManagementService {

    private static final Logger log = LoggerFactory.getLogger(LinkManagementService.class);

    private static final String SELECT_LINKS_CQL = """
            SELECT short_key, long_url, short_url, expires_at, created_at
            FROM url_mapping_by_user
            WHERE user_id = ?
            ORDER BY created_at DESC
            """;

    private final CqlSession cqlSession;

    private PreparedStatement selectLinksStmt;

    public LinkManagementService(CqlSession cqlSession) {
        this.cqlSession = cqlSession;
    }

    /**
     * Prepares the CQL statement after the Spring context is fully wired.
     * Preparation validates the CQL against the live schema and compiles a
     * query plan — done once at startup to amortise the cost over all requests.
     */
    @PostConstruct
    public void prepareStatements() {
        try {
            selectLinksStmt = cqlSession.prepare(SELECT_LINKS_CQL);
            log.info("LinkManagementService CQL statement prepared.");
        } catch (Exception e) {
            // Non-fatal: log and allow the service to start; requests will fail gracefully
            log.error("Failed to prepare LinkManagementService CQL statement — "
                    + "url_mapping_by_user may not exist yet: {}", e.getMessage());
        }
    }

    /**
     * Returns a page of shortened URLs belonging to {@code userId}.
     *
     * @param userId the owning user's identifier
     * @param page   zero-based page index
     * @param size   number of results per page (max 100 enforced internally)
     * @return ordered (newest-first) list of {@link ShortenResponse}; may be empty
     */
    public List<ShortenResponse> getUserLinks(Long userId, int page, int size) {
        if (selectLinksStmt == null) {
            log.warn("CQL statement not prepared — returning empty list for userId={}", userId);
            return List.of();
        }

        int effectiveSize = Math.min(size, 100);
        int skip          = page * effectiveSize;

        log.debug("getUserLinks — userId={}, page={}, size={}", userId, page, effectiveSize);

        try {
            BoundStatement bound = selectLinksStmt.bind(userId)
                    .setPageSize(skip + effectiveSize);   // fetch enough to skip + serve

            ResultSet rs = cqlSession.execute(bound);

            List<ShortenResponse> results = new ArrayList<>(effectiveSize);
            int rowIndex = 0;
            for (Row row : rs) {
                if (rowIndex++ < skip) {
                    continue; // skip earlier pages
                }
                if (results.size() >= effectiveSize) {
                    break;
                }
                results.add(mapRow(row));
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to fetch user links for userId={}: {}", userId, e.getMessage(), e);
            return List.of();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ShortenResponse mapRow(Row row) {
        String shortKey  = row.getString("short_key");
        String longUrl   = row.getString("long_url");
        String shortUrl  = row.getString("short_url");
        Instant createdAt = toInstant(row, "created_at");
        Instant expiresAt = toInstant(row, "expires_at"); // nullable

        // Fallback for null shortUrl (older rows before field was added)
        if (shortUrl == null) {
            shortUrl = shortKey;
        }

        return new ShortenResponse(shortUrl, shortKey, longUrl, expiresAt, createdAt);
    }

    /**
     * Safely extracts an {@link Instant} from a Cassandra row column.
     *
     * <p>Cassandra {@code timestamp} columns map to {@link java.time.Instant}
     * via {@link Row#getInstant(String)}. Returns {@link Instant#EPOCH} when the
     * column is {@code null} or missing (defensive handling for schema evolution).
     */
    private Instant toInstant(Row row, String column) {
        try {
            Instant value = row.getInstant(column);
            return value != null ? value : Instant.EPOCH;
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
