package com.urlshortener.write.config;

import com.datastax.oss.driver.api.core.CqlSession;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Runs the bundled {@code db/schema.cql} script at application startup to ensure
 * the keyspace, tables, and materialized views exist in ScyllaDB.
 *
 * <p>Each CQL statement is executed sequentially.  Statements that have already
 * been applied are idempotent ({@code IF NOT EXISTS}) so this is safe to run on
 * every pod startup in a rolling deployment.
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private static final String SCHEMA_RESOURCE = "db/schema.cql";

    private final CqlSession cqlSession;

    public SchemaInitializer(CqlSession cqlSession) {
        this.cqlSession = cqlSession;
    }

    @PostConstruct
    public void initSchema() throws IOException {
        log.info("Running ScyllaDB schema initialisation from {}", SCHEMA_RESOURCE);

        ClassPathResource resource = new ClassPathResource(SCHEMA_RESOURCE);
        String cql = resource.getContentAsString(StandardCharsets.UTF_8);

        // Split on semicolons, trim whitespace, skip empty/comment-only blocks
        String[] statements = cql.split(";");
        int executed = 0;
        for (String raw : statements) {
            String stmt = raw.strip();
            if (stmt.isEmpty() || stmt.startsWith("--")) {
                continue;
            }
            log.debug("Executing CQL: {}", stmt.lines().findFirst().orElse(""));
            cqlSession.execute(stmt);
            executed++;
        }

        log.info("Schema initialisation complete — {} statements executed", executed);
    }
}
