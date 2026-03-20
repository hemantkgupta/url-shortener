-- ============================================================
-- Write Service — ScyllaDB / Cassandra schema
-- Applied automatically at startup by SchemaInitializer.
-- All DDL statements use IF NOT EXISTS for idempotency.
-- ============================================================

-- Primary mapping: short key → long URL
CREATE TABLE IF NOT EXISTS __KEYSPACE__.url_mapping (
    short_key  text PRIMARY KEY,
    long_url   text,
    user_id    bigint,
    created_at timestamp,
    expires_at timestamp,
    is_active  boolean
) WITH default_time_to_live = 63072000
  AND compaction = {'class': 'LeveledCompactionStrategy'};

-- Alias / vanity-key mapping
CREATE TABLE IF NOT EXISTS __KEYSPACE__.alias_mapping (
    alias      text PRIMARY KEY,
    short_key  text,
    user_id    bigint,
    created_at timestamp
);

-- Per-user lookup table for analytics / management UI.
-- Maintained by the write-service alongside the primary url_mapping table.
CREATE TABLE IF NOT EXISTS __KEYSPACE__.url_mapping_by_user (
    user_id    bigint,
    created_at timestamp,
    short_key  text,
    long_url   text,
    short_url  text,
    expires_at timestamp,
    PRIMARY KEY (user_id, created_at, short_key)
) WITH CLUSTERING ORDER BY (created_at DESC, short_key ASC)
  AND compaction = {'class': 'LeveledCompactionStrategy'};
