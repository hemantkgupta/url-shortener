package com.urlshortener.write.config;

import com.datastax.oss.driver.api.core.CqlSession;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Collectors;

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
    private static final String KEYSPACE_PLACEHOLDER = "__KEYSPACE__";

    private final CqlSession cqlSession;
    private final WriteServiceProperties properties;

    public SchemaInitializer(CqlSession cqlSession, WriteServiceProperties properties) {
        this.cqlSession = cqlSession;
        this.properties = properties;
    }

    @PostConstruct
    public void initSchema() throws IOException {
        log.info("Running ScyllaDB schema initialisation from {}", SCHEMA_RESOURCE);

        WriteServiceProperties.Cassandra cassandra = properties.getCassandra();
        String keyspace = cassandra.getKeyspace();

        cqlSession.execute(buildCreateKeyspaceStatement(cassandra));
        log.info("Ensured keyspace exists: {}", keyspace);

        ClassPathResource resource = new ClassPathResource(SCHEMA_RESOURCE);
        String cql = resource.getContentAsString(StandardCharsets.UTF_8)
                .replace(KEYSPACE_PLACEHOLDER, keyspace)
                .lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));

        // Split on semicolons, trim whitespace, skip empty blocks
        String[] statements = cql.split(";");
        int executed = 0;
        for (String raw : statements) {
            String stmt = raw.strip();
            if (stmt.isEmpty()) {
                continue;
            }
            log.debug("Executing CQL: {}", stmt.lines().findFirst().orElse(""));
            cqlSession.execute(stmt);
            executed++;
        }

        log.info("Schema initialisation complete — {} statements executed", executed);
    }

    private String buildCreateKeyspaceStatement(WriteServiceProperties.Cassandra cassandra) {
        String strategy = cassandra.getSchemaReplicationStrategy();
        String keyspace = cassandra.getKeyspace();

        String replicationClause;
        if ("NetworkTopologyStrategy".equalsIgnoreCase(strategy)) {
            replicationClause = String.format(
                    Locale.ROOT,
                    "{'class': 'NetworkTopologyStrategy', '%s': %d}",
                    cassandra.getDatacenter(),
                    cassandra.getSchemaReplicationFactor());
        } else {
            replicationClause = String.format(
                    Locale.ROOT,
                    "{'class': 'SimpleStrategy', 'replication_factor': %d}",
                    cassandra.getSchemaReplicationFactor());
        }

        return String.format(
                Locale.ROOT,
                "CREATE KEYSPACE IF NOT EXISTS %s WITH replication = %s AND durable_writes = true",
                keyspace,
                replicationClause);
    }
}
