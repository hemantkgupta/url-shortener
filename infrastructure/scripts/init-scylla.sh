#!/usr/bin/env bash
# =============================================================================
# init-scylla.sh — Initialise ScyllaDB schema for the URL shortener
# =============================================================================
# Creates:
#   - Keyspace: url_shortener
#   - Table:    url_mapping       (primary URL store, 2-year default TTL)
#   - Table:    alias_mapping     (human-readable aliases)
#   - MV:       url_mapping_by_user  (per-user URL listing, newest first)
#
# Usage:
#   ./infrastructure/scripts/init-scylla.sh
#
# Requirements:
#   - ScyllaDB container named "scylladb" must be running (via docker-compose)
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# Colour helpers
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

log()     { echo -e "${BLUE}[INFO ]${NC}  $*"; }
success() { echo -e "${GREEN}[OK   ]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN ]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC}  $*" >&2; }

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
CONTAINER_NAME="${SCYLLA_CONTAINER:-scylladb}"
TIMEOUT="${SCYLLA_INIT_TIMEOUT:-120}"
POLL_INTERVAL=5

# ---------------------------------------------------------------------------
# Wait for ScyllaDB to become available
# ---------------------------------------------------------------------------
wait_for_scylla() {
    local elapsed=0
    log "Waiting for ScyllaDB container '${CONTAINER_NAME}' to accept CQL connections (timeout: ${TIMEOUT}s)..."

    while true; do
        if docker exec "${CONTAINER_NAME}" cqlsh \
               --execute "SELECT now() FROM system.local" \
               >/dev/null 2>&1; then
            success "ScyllaDB is ready"
            return 0
        fi

        if (( elapsed >= TIMEOUT )); then
            error "Timed out after ${TIMEOUT}s waiting for ScyllaDB."
            error "Check container logs: docker logs ${CONTAINER_NAME}"
            exit 1
        fi

        printf "  Waiting for ScyllaDB... (%ds/%ds)\r" "${elapsed}" "${TIMEOUT}"
        sleep "${POLL_INTERVAL}"
        (( elapsed += POLL_INTERVAL ))
    done
}

# ---------------------------------------------------------------------------
# Run CQL statements
# ---------------------------------------------------------------------------
run_cql() {
    local description="$1"
    local cql="$2"

    log "Executing: ${description}"
    docker exec "${CONTAINER_NAME}" cqlsh --execute "${cql}"
    success "${description} — done"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    echo ""
    echo -e "${BOLD}==> ScyllaDB Schema Initialisation${NC}"
    echo ""

    # Verify the container exists and is running
    if ! docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
        error "Container '${CONTAINER_NAME}' does not exist."
        error "Start the infrastructure first: ./infrastructure/scripts/start-infra.sh"
        exit 1
    fi

    wait_for_scylla

    # -----------------------------------------------------------------------
    # 1. Keyspace
    # -----------------------------------------------------------------------
    run_cql "Create keyspace url_shortener" \
        "CREATE KEYSPACE IF NOT EXISTS url_shortener
         WITH replication = {
             'class': 'SimpleStrategy',
             'replication_factor': 1
         }
         AND durable_writes = true;"

    # -----------------------------------------------------------------------
    # 2. url_mapping table
    #    - Primary store for short_key → long_url resolution
    #    - default_time_to_live = 63072000 seconds (2 years)
    #    - Individual rows can override TTL at write time
    # -----------------------------------------------------------------------
    run_cql "Create table url_shortener.url_mapping" \
        "CREATE TABLE IF NOT EXISTS url_shortener.url_mapping (
             short_key   text        PRIMARY KEY,
             long_url    text,
             user_id     bigint,
             created_at  timestamp,
             expires_at  timestamp,
             is_active   boolean
         )
         WITH default_time_to_live = 63072000
         AND comment = 'Primary URL mapping table — short_key is globally unique'
         AND compaction = {
             'class': 'LeveledCompactionStrategy'
         }
         AND compression = {
             'sstable_compression': 'LZ4Compressor'
         };"

    # -----------------------------------------------------------------------
    # 3. alias_mapping table
    #    - Stores human-readable custom aliases → canonical short_key
    # -----------------------------------------------------------------------
    run_cql "Create table url_shortener.alias_mapping" \
        "CREATE TABLE IF NOT EXISTS url_shortener.alias_mapping (
             alias       text        PRIMARY KEY,
             short_key   text,
             user_id     bigint,
             created_at  timestamp
         )
         WITH comment = 'Custom alias → short_key lookup'
         AND compaction = {
             'class': 'LeveledCompactionStrategy'
         }
         AND compression = {
             'sstable_compression': 'LZ4Compressor'
         };"

    # -----------------------------------------------------------------------
    # 4. Materialized view: url_mapping_by_user
    #    - Enables efficient "list URLs by user" queries, sorted by recency
    #    - Primary key: (user_id, created_at DESC, short_key)
    # -----------------------------------------------------------------------
    run_cql "Create materialized view url_shortener.url_mapping_by_user" \
        "CREATE MATERIALIZED VIEW IF NOT EXISTS url_shortener.url_mapping_by_user
         AS SELECT *
         FROM url_shortener.url_mapping
         WHERE user_id IS NOT NULL
           AND short_key IS NOT NULL
           AND created_at IS NOT NULL
         PRIMARY KEY (user_id, created_at, short_key)
         WITH CLUSTERING ORDER BY (created_at DESC, short_key ASC)
         AND comment = 'Per-user URL list ordered by creation time (newest first)'
         AND compaction = {
             'class': 'LeveledCompactionStrategy'
         };"

    # -----------------------------------------------------------------------
    # Verification
    # -----------------------------------------------------------------------
    echo ""
    log "Verifying created objects..."
    docker exec "${CONTAINER_NAME}" cqlsh --execute \
        "DESCRIBE KEYSPACE url_shortener;" | grep -E "url_mapping|alias_mapping|url_mapping_by_user" \
        && success "Schema verification passed" \
        || warn "Could not verify all objects — check manually: docker exec ${CONTAINER_NAME} cqlsh"

    echo ""
    echo -e "${BOLD}==> ScyllaDB initialisation complete${NC}"
    echo ""
    echo "  Keyspace : url_shortener"
    echo "  Tables   : url_mapping, alias_mapping"
    echo "  Views    : url_mapping_by_user"
    echo ""
}

main "$@"
