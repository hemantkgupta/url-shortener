#!/usr/bin/env bash
# =============================================================================
# start-infra.sh — Bring up the full local URL-shortener infrastructure stack
# =============================================================================
set -euo pipefail

START_SIGNOZ=true

for arg in "$@"; do
  case "${arg}" in
    --core-only)
      START_SIGNOZ=false
      ;;
    --help|-h)
      cat <<'EOF'
Usage: ./infrastructure/scripts/start-infra.sh [--core-only]

Options:
  --core-only   Start only the app dependencies needed for local development
                (ScyllaDB, Redis, etcd, Kafka, Kafka UI, ClickHouse).
                Skips SigNoz and the OTel collector.
EOF
      exit 0
      ;;
    *)
      echo "[WARN ]  Unknown flag: ${arg} (ignored)"
      ;;
  esac
done

# ---------------------------------------------------------------------------
# Colour helpers
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Colour

log()     { echo -e "${BLUE}[INFO ]${NC}  $*"; }
success() { echo -e "${GREEN}[OK   ]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN ]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC}  $*" >&2; }
header()  { echo -e "\n${BOLD}${CYAN}==> $*${NC}"; }

# ---------------------------------------------------------------------------
# Resolve project root (works regardless of cwd)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/infrastructure/compose/docker-compose.dev.yml"

# ---------------------------------------------------------------------------
# Configurable timeouts (seconds)
# ---------------------------------------------------------------------------
SCYLLA_TIMEOUT=120
REDIS_TIMEOUT=30
KAFKA_TIMEOUT=90
CLICKHOUSE_TIMEOUT=60
ETCD_TIMEOUT=30
SIGNOZ_TIMEOUT=120

# ---------------------------------------------------------------------------
# 1. Pre-flight checks
# ---------------------------------------------------------------------------
header "Pre-flight checks"

check_docker() {
  if ! docker info >/dev/null 2>&1; then
    error "Docker daemon is not running. Start Docker Desktop (or dockerd) and try again."
    exit 1
  fi
  success "Docker daemon is running"
}

check_compose_file() {
  if [[ ! -f "${COMPOSE_FILE}" ]]; then
    error "Compose file not found: ${COMPOSE_FILE}"
    exit 1
  fi
  success "Compose file found: ${COMPOSE_FILE}"
}

check_port() {
  local port=$1 service=$2
  if lsof -iTCP:"${port}" -sTCP:LISTEN -P -n >/dev/null 2>&1; then
    warn "Port ${port} is already in use (needed by ${service}). Continuing anyway."
  fi
}

check_docker
check_compose_file

log "Checking port availability..."
check_port 9042  "ScyllaDB CQL"
check_port 6379  "Redis"
check_port 2379  "etcd"
check_port 9092  "Kafka"
check_port 9080  "Kafka UI"
check_port 8123  "ClickHouse HTTP"
check_port 4317  "OTel gRPC"
check_port 8888  "SigNoz API"
check_port 3301  "SigNoz Frontend"

container_exists() {
  docker inspect "$1" >/dev/null 2>&1
}

remove_conflicting_container() {
  local name="$1"
  if container_exists "${name}"; then
    warn "Removing existing container '${name}' to avoid name conflicts"
    docker rm -f "${name}" >/dev/null
  fi
}

prepare_named_containers() {
  local names=(
    scylladb
    redis
    etcd
    kafka
    kafka-ui
    clickhouse
  )

  if ${START_SIGNOZ}; then
    names+=(
      signoz-otel-collector
      signoz
      signoz-frontend
    )
  fi

  for name in "${names[@]}"; do
    remove_conflicting_container "${name}"
  done
}

# ---------------------------------------------------------------------------
# 2. Start Docker Compose stack
# ---------------------------------------------------------------------------
header "Starting infrastructure stack"
prepare_named_containers
if ${START_SIGNOZ}; then
  log "Running: docker compose -f ${COMPOSE_FILE} up -d"
  docker compose -f "${COMPOSE_FILE}" up -d
else
  log "Running core-only infrastructure without SigNoz"
  docker compose -f "${COMPOSE_FILE}" up -d \
    scylladb redis etcd kafka kafka-ui clickhouse
fi

# ---------------------------------------------------------------------------
# 3. Health-poll helpers
# ---------------------------------------------------------------------------

# Poll until a Docker container's health status is "healthy", or timeout.
wait_for_healthy() {
  local container=$1
  local timeout=$2
  local elapsed=0
  local interval=5

  log "Waiting for ${BOLD}${container}${NC} to become healthy (timeout: ${timeout}s)..."
  while true; do
    local status
    status=$(docker inspect --format='{{.State.Health.Status}}' "${container}" 2>/dev/null || echo "missing")

    case "${status}" in
      healthy)
        success "${container} is healthy"
        return 0
        ;;
      unhealthy)
        error "${container} entered 'unhealthy' state. Run: docker logs ${container}"
        return 1
        ;;
      starting|missing)
        : # keep waiting
        ;;
      *)
        warn "${container} health status: ${status}"
        ;;
    esac

    if (( elapsed >= timeout )); then
      error "Timed out waiting for ${container} (${timeout}s). Run: docker logs ${container}"
      return 1
    fi

    sleep "${interval}"
    (( elapsed += interval ))
    printf "  %s waiting... (%ds/%ds)\r" "${container}" "${elapsed}" "${timeout}"
  done
}

# Poll a URL until it returns HTTP 2xx.
wait_for_http() {
  local name=$1 url=$2 timeout=$3
  local elapsed=0 interval=5

  log "Waiting for ${BOLD}${name}${NC} at ${url} (timeout: ${timeout}s)..."
  while ! curl -sf --max-time 3 "${url}" >/dev/null 2>&1; do
    if (( elapsed >= timeout )); then
      warn "Timed out waiting for ${name} HTTP endpoint"
      return 1
    fi
    sleep "${interval}"
    (( elapsed += interval ))
    printf "  %s waiting... (%ds/%ds)\r" "${name}" "${elapsed}" "${timeout}"
  done
  success "${name} is responding at ${url}"
}

# ---------------------------------------------------------------------------
# 4. Wait for each service
# ---------------------------------------------------------------------------
header "Waiting for services to become healthy"

wait_for_healthy "scylladb"   "${SCYLLA_TIMEOUT}"
wait_for_healthy "redis"      "${REDIS_TIMEOUT}"
wait_for_healthy "etcd"       "${ETCD_TIMEOUT}"
wait_for_healthy "kafka"      "${KAFKA_TIMEOUT}"
wait_for_healthy "clickhouse" "${CLICKHOUSE_TIMEOUT}"

if ${START_SIGNOZ}; then
  # SigNoz — poll HTTP endpoint (health check in compose may take longer)
  wait_for_http "SigNoz query-service" "http://localhost:8888/api/v1/health" "${SIGNOZ_TIMEOUT}" || \
    warn "SigNoz may still be initialising. Check: docker logs signoz"

  wait_for_http "SigNoz frontend" "http://localhost:3301" 30 || \
    warn "SigNoz frontend may still be starting."
fi

# ---------------------------------------------------------------------------
# 5. Summary
# ---------------------------------------------------------------------------
header "Infrastructure is ready"
echo ""
echo -e "  ${BOLD}Service              URL${NC}"
echo    "  ─────────────────────────────────────────────────────────"
echo -e "  ScyllaDB (CQL)       ${CYAN}localhost:9042${NC}"
echo -e "  Redis                ${CYAN}localhost:6379${NC}"
echo -e "  RedisInsight UI      ${CYAN}http://localhost:8001${NC}"
echo -e "  etcd                 ${CYAN}localhost:2379${NC}"
echo -e "  Kafka (broker)       ${CYAN}localhost:9092${NC}"
echo -e "  Kafka UI             ${CYAN}http://localhost:9080${NC}"
echo -e "  ClickHouse HTTP      ${CYAN}http://localhost:8123${NC}"
echo -e "  ClickHouse Native    ${CYAN}localhost:9000${NC}"
if ${START_SIGNOZ}; then
  echo -e "  OTel Collector gRPC  ${CYAN}localhost:4317${NC}"
  echo -e "  OTel Collector HTTP  ${CYAN}localhost:4318${NC}"
  echo -e "  SigNoz API           ${CYAN}http://localhost:8888${NC}"
  echo -e "  SigNoz UI            ${CYAN}http://localhost:3301${NC}"
fi
echo ""
echo -e "  ${BOLD}Next steps:${NC}"
echo -e "    Init Scylla schema: ${YELLOW}./infrastructure/scripts/init-scylla.sh${NC}"
echo -e "    Start services:     ${YELLOW}./infrastructure/scripts/start-services.sh${NC}"
echo -e "    Frontend dev server:${YELLOW}cd frontend && npm run dev${NC}"
echo ""
