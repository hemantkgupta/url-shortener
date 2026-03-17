#!/usr/bin/env bash
# =============================================================================
# start-services.sh — Start all four Spring Boot microservices locally
# =============================================================================
# Starts (in background, with dev profile):
#   1. key-generation-service  → port 8081
#   2. write-service           → port 8082
#   3. redirect-service        → port 8080
#   4. analytics-service       → port 8083
#
# Logs are written to logs/<service>.log
# PIDs are written to logs/<service>.pid
#
# Usage:
#   ./infrastructure/scripts/start-services.sh
#
# Requirements:
#   - Infrastructure stack running (run start-infra.sh first)
#   - Java 21+ on PATH
#   - Gradle wrapper present at project root
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# Colour helpers
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log()     { echo -e "${BLUE}[INFO ]${NC}  $*"; }
success() { echo -e "${GREEN}[OK   ]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN ]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC}  $*" >&2; }
header()  { echo -e "\n${BOLD}${CYAN}==> $*${NC}"; }

# ---------------------------------------------------------------------------
# Resolve paths
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOGS_DIR="${PROJECT_ROOT}/logs"

mkdir -p "${LOGS_DIR}"

# ---------------------------------------------------------------------------
# Service definitions: name  gradle-module  port  spring-profile
# ---------------------------------------------------------------------------
declare -a SERVICE_NAMES=(
    "key-generation-service"
    "write-service"
    "redirect-service"
    "analytics-service"
)

declare -A SERVICE_MODULE=(
    ["key-generation-service"]="key-generation-service"
    ["write-service"]="write-service"
    ["redirect-service"]="redirect-service"
    ["analytics-service"]="analytics-service"
)

declare -A SERVICE_PORT=(
    ["key-generation-service"]="8081"
    ["write-service"]="8082"
    ["redirect-service"]="8080"
    ["analytics-service"]="8083"
)

declare -A SERVICE_PROFILE=(
    ["key-generation-service"]="dev"
    ["write-service"]="dev"
    ["redirect-service"]="dev"
    ["analytics-service"]="dev"
)

# ---------------------------------------------------------------------------
# Check if a port is already in use
# ---------------------------------------------------------------------------
port_in_use() {
    local port=$1
    lsof -iTCP:"${port}" -sTCP:LISTEN -P -n >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# Start a single service
# ---------------------------------------------------------------------------
start_service() {
    local name="$1"
    local module="${SERVICE_MODULE[$name]}"
    local port="${SERVICE_PORT[$name]}"
    local profile="${SERVICE_PROFILE[$name]}"
    local log_file="${LOGS_DIR}/${name}.log"
    local pid_file="${LOGS_DIR}/${name}.pid"

    if port_in_use "${port}"; then
        warn "Port ${port} already in use — ${name} may already be running. Skipping."
        return 0
    fi

    log "Starting ${BOLD}${name}${NC} on port ${port} (profile: ${profile})..."

    cd "${PROJECT_ROOT}"
    ./gradlew ":${module}:bootRun" \
        --args="--spring.profiles.active=${profile}" \
        --no-daemon \
        > "${log_file}" 2>&1 &

    local pid=$!
    echo "${pid}" > "${pid_file}"
    success "${name} started (PID ${pid}) — log: ${log_file}"
}

# ---------------------------------------------------------------------------
# Wait for a service HTTP endpoint to respond
# ---------------------------------------------------------------------------
wait_for_service() {
    local name="$1"
    local port="${SERVICE_PORT[$name]}"
    local url="http://localhost:${port}/actuator/health"
    local timeout=60
    local elapsed=0
    local interval=3

    log "Waiting for ${name} to become ready at ${url}..."
    while ! curl -sf --max-time 2 "${url}" >/dev/null 2>&1; do
        if (( elapsed >= timeout )); then
            warn "Timed out waiting for ${name} (${timeout}s). Check: ${LOGS_DIR}/${name}.log"
            return 1
        fi
        sleep "${interval}"
        (( elapsed += interval ))
        printf "  Waiting for %s... (%ds/%ds)\r" "${name}" "${elapsed}" "${timeout}"
    done
    success "${name} is ready"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    header "URL Shortener — Starting Spring Boot Services"

    # Sanity checks
    if [[ ! -f "${PROJECT_ROOT}/gradlew" ]]; then
        error "Gradle wrapper not found at ${PROJECT_ROOT}/gradlew"
        exit 1
    fi

    if ! java -version >/dev/null 2>&1; then
        error "Java is not available on PATH. Install Java 21+."
        exit 1
    fi

    echo ""
    log "Project root : ${PROJECT_ROOT}"
    log "Log directory: ${LOGS_DIR}"
    echo ""

    # Start all services
    for name in "${SERVICE_NAMES[@]}"; do
        start_service "${name}"
    done

    echo ""
    header "Waiting for services to become healthy"

    # Wait for each service in order (KGS first, since write-service may depend on it)
    for name in "${SERVICE_NAMES[@]}"; do
        wait_for_service "${name}" || true
    done

    # ---------------------------------------------------------------------------
    # Summary
    # ---------------------------------------------------------------------------
    header "All services are up"
    echo ""
    echo -e "  ${BOLD}Service                 URL                        Log${NC}"
    echo    "  ─────────────────────────────────────────────────────────────────────────"
    echo -e "  key-generation-service  ${CYAN}http://localhost:8081${NC}      ${LOGS_DIR}/key-generation-service.log"
    echo -e "  write-service           ${CYAN}http://localhost:8082${NC}      ${LOGS_DIR}/write-service.log"
    echo -e "  redirect-service        ${CYAN}http://localhost:8080${NC}      ${LOGS_DIR}/redirect-service.log"
    echo -e "  analytics-service       ${CYAN}http://localhost:8083${NC}      ${LOGS_DIR}/analytics-service.log"
    echo ""
    echo -e "  ${BOLD}Actuator health endpoints:${NC}"
    echo -e "    KGS      : ${CYAN}http://localhost:8081/actuator/health${NC}"
    echo -e "    Write    : ${CYAN}http://localhost:8082/actuator/health${NC}"
    echo -e "    Redirect : ${CYAN}http://localhost:8080/actuator/health${NC}"
    echo -e "    Analytics: ${CYAN}http://localhost:8083/actuator/health${NC}"
    echo ""
    echo -e "  To stop all services: ${YELLOW}./infrastructure/scripts/stop-all.sh${NC}"
    echo ""
}

main "$@"
