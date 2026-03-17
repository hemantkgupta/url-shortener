#!/usr/bin/env bash
# =============================================================================
# init-clickhouse.sh — Initialise ClickHouse analytics schema
# =============================================================================
# Creates:
#   - Database: analytics
#   - Table:    analytics.click_events   (raw click event store, 90-day TTL)
#   - MV:       analytics.click_counts_by_day  (aggregated daily click counts)
#
# Usage:
#   ./infrastructure/scripts/init-clickhouse.sh
#
# Requirements:
#   - ClickHouse container named "clickhouse" must be running (via docker-compose)
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
CONTAINER_NAME="${CLICKHOUSE_CONTAINER:-clickhouse}"
TIMEOUT="${CLICKHOUSE_INIT_TIMEOUT:-90}"
POLL_INTERVAL=5

# ---------------------------------------------------------------------------
# Wait for ClickHouse to become available
# ---------------------------------------------------------------------------
wait_for_clickhouse() {
    local elapsed=0
    log "Waiting for ClickHouse container '${CONTAINER_NAME}' to be ready (timeout: ${TIMEOUT}s)..."

    while true; do
        if docker exec "${CONTAINER_NAME}" clickhouse-client \
               --query "SELECT 1" >/dev/null 2>&1; then
            success "ClickHouse is ready"
            return 0
        fi

        if (( elapsed >= TIMEOUT )); then
            error "Timed out after ${TIMEOUT}s waiting for ClickHouse."
            error "Check container logs: docker logs ${CONTAINER_NAME}"
            exit 1
        fi

        printf "  Waiting for ClickHouse... (%ds/%ds)\r" "${elapsed}" "${TIMEOUT}"
        sleep "${POLL_INTERVAL}"
        (( elapsed += POLL_INTERVAL ))
    done
}

# ---------------------------------------------------------------------------
# Run a SQL statement via clickhouse-client
# ---------------------------------------------------------------------------
run_sql() {
    local description="$1"
    local sql="$2"

    log "Executing: ${description}"
    docker exec "${CONTAINER_NAME}" clickhouse-client \
        --multiline \
        --multiquery \
        --query "${sql}"
    success "${description} — done"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    echo ""
    echo -e "${BOLD}==> ClickHouse Schema Initialisation${NC}"
    echo ""

    # Verify the container exists
    if ! docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
        error "Container '${CONTAINER_NAME}' does not exist."
        error "Start the infrastructure first: ./infrastructure/scripts/start-infra.sh"
        exit 1
    fi

    wait_for_clickhouse

    # -----------------------------------------------------------------------
    # 1. Database
    # -----------------------------------------------------------------------
    run_sql "Create database analytics" \
        "CREATE DATABASE IF NOT EXISTS analytics;"

    # -----------------------------------------------------------------------
    # 2. click_events table
    #    - Raw click events from Kafka consumer (analytics-service)
    #    - Partitioned by month for efficient TTL and range scans
    #    - Ordered by (short_key, event_time) for per-key time-series queries
    #    - 90-day TTL: older events are automatically purged
    # -----------------------------------------------------------------------
    run_sql "Create table analytics.click_events" \
        "CREATE TABLE IF NOT EXISTS analytics.click_events (
             short_key   LowCardinality(String),
             event_time  DateTime,
             country     LowCardinality(String),
             city        String,
             referrer    String,
             device      LowCardinality(String),
             browser     LowCardinality(String),
             ip_hash     String
         )
         ENGINE = MergeTree()
         PARTITION BY toYYYYMM(event_time)
         ORDER BY (short_key, event_time)
         TTL event_time + INTERVAL 90 DAY
         SETTINGS index_granularity = 8192;"

    # -----------------------------------------------------------------------
    # 3. click_counts_mv — backing table for the materialized view
    #    SummingMergeTree accumulates incremental counts per (short_key, date)
    # -----------------------------------------------------------------------
    run_sql "Create backing table analytics.click_counts_by_day" \
        "CREATE TABLE IF NOT EXISTS analytics.click_counts_by_day (
             short_key   LowCardinality(String),
             date        Date,
             clicks      UInt64
         )
         ENGINE = SummingMergeTree(clicks)
         ORDER BY (short_key, date)
         SETTINGS index_granularity = 8192;"

    # -----------------------------------------------------------------------
    # 4. Materialized view — populates click_counts_by_day from click_events
    #    Inserts are incrementally aggregated; reads use FINAL or pre-merge
    # -----------------------------------------------------------------------
    run_sql "Create materialized view analytics.click_counts_mv" \
        "CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.click_counts_mv
         TO analytics.click_counts_by_day
         AS SELECT
             short_key,
             toDate(event_time) AS date,
             count()            AS clicks
         FROM analytics.click_events
         GROUP BY short_key, date;"

    # -----------------------------------------------------------------------
    # Verification
    # -----------------------------------------------------------------------
    echo ""
    log "Verifying created objects..."
    docker exec "${CONTAINER_NAME}" clickhouse-client \
        --query "SELECT name, engine FROM system.tables WHERE database = 'analytics' ORDER BY name;" \
        && success "Schema verification passed" \
        || warn "Verification query failed — check manually: docker exec ${CONTAINER_NAME} clickhouse-client"

    echo ""
    echo -e "${BOLD}==> ClickHouse initialisation complete${NC}"
    echo ""
    echo "  Database  : analytics"
    echo "  Tables    : click_events, click_counts_by_day"
    echo "  Views     : click_counts_mv (materialized)"
    echo ""
    echo "  HTTP interface  : http://localhost:8123"
    echo "  Native TCP      : localhost:9000"
    echo ""
}

main "$@"
