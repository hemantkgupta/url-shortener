-- ============================================================
-- Write Service — ScyllaDB / Cassandra schema
-- Applied automatically at startup by SchemaInitializer.
-- All DDL statements use IF NOT EXISTS for idempotency.
-- ============================================================

CREATE KEYSPACE IF NOT EXISTS url_shortener
  WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3}
  AND durable_writes = true;

USE url_shortener;

-- Primary mapping: short key → long URL
CREATE TABLE IF NOT EXISTS url_mapping (
    short_key  text PRIMARY KEY,
    long_url   text,
    user_id    bigint,
    created_at timestamp,
    expires_at timestamp,
    is_active  boolean
) WITH default_time_to_live = 63072000
  AND compaction = {'class': 'LeveledCompactionStrategy'};

-- Alias / vanity-key mapping
CREATE TABLE IF NOT EXISTS alias_mapping (
    alias      text PRIMARY KEY,
    short_key  text,
    user_id    bigint,
    created_at timestamp
);

-- Materialized view for per-user URL lookups (analytics / management UI)
CREATE MATERIALIZED VIEW IF NOT EXISTS url_mapping_by_user AS
    SELECT * FROM url_mapping
    WHERE user_id IS NOT NULL AND short_key IS NOT NULL
    PRIMARY KEY (user_id, created_at, short_key)
    WITH CLUSTERING ORDER BY (created_at DESC);
