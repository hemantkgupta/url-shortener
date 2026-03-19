package com.urlshortener.analytics.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.urlshortener.core.exception.KeyNotFoundException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class LinkOwnershipService {

    private static final Logger log = LoggerFactory.getLogger(LinkOwnershipService.class);
    private static final String SELECT_OWNER_CQL = """
            SELECT user_id
            FROM url_mapping
            WHERE short_key = ?
            """;

    private final CqlSession cqlSession;
    private PreparedStatement selectOwnerStmt;

    public LinkOwnershipService(CqlSession cqlSession) {
        this.cqlSession = cqlSession;
    }

    @PostConstruct
    void prepareStatements() {
        selectOwnerStmt = cqlSession.prepare(SELECT_OWNER_CQL);
    }

    public void assertOwner(String shortKey, long userId) {
        Row row = cqlSession.execute(selectOwnerStmt.bind(shortKey)).one();
        if (row == null || row.isNull("user_id")) {
            throw new KeyNotFoundException(shortKey);
        }

        long ownerId = row.getLong("user_id");
        if (ownerId != userId) {
            log.warn("Ownership check failed for shortKey={} ownerId={} requesterId={}",
                    shortKey, ownerId, userId);
            throw new AccessDeniedException("You do not own short key '" + shortKey + "'");
        }
    }
}
