CREATE KEYSPACE IF NOT EXISTS url_shortener
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS url_shortener.url_mapping (
    short_key  TEXT PRIMARY KEY,
    long_url   TEXT,
    user_id    BIGINT,
    created_at TIMESTAMP,
    expires_at TIMESTAMP,
    is_active  BOOLEAN
);
