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
LOCAL_ENV_FILE="${HOME}/.url-shortener-local-dev"

mkdir -p "${LOGS_DIR}"

# ---------------------------------------------------------------------------
# Load local development environment if present
# ---------------------------------------------------------------------------
if [[ -f "${LOCAL_ENV_FILE}" ]]; then
    log "Loading local environment from ${LOCAL_ENV_FILE}"
    set -a
    # shellcheck disable=SC1090
    source "${LOCAL_ENV_FILE}"
    set +a
fi

export URL_SHORTENER_KEY_GENERATION_SERVICE_PORT="${URL_SHORTENER_KEY_GENERATION_SERVICE_PORT:-18081}"
export URL_SHORTENER_WRITE_SERVICE_PORT="${URL_SHORTENER_WRITE_SERVICE_PORT:-18082}"
export URL_SHORTENER_REDIRECT_SERVICE_PORT="${URL_SHORTENER_REDIRECT_SERVICE_PORT:-18080}"
export URL_SHORTENER_ANALYTICS_SERVICE_PORT="${URL_SHORTENER_ANALYTICS_SERVICE_PORT:-18083}"
export URL_SHORTENER_FRONTEND_PORT="${URL_SHORTENER_FRONTEND_PORT:-13000}"
export URL_SHORTENER_SERVICE_START_TIMEOUT_SECONDS="${URL_SHORTENER_SERVICE_START_TIMEOUT_SECONDS:-120}"
export KGS_BASE_URL="${KGS_BASE_URL:-http://localhost:${URL_SHORTENER_KEY_GENERATION_SERVICE_PORT}}"
export OWN_DOMAIN="${OWN_DOMAIN:-localhost:${URL_SHORTENER_FRONTEND_PORT}/r}"

JAVA_BIN=""

# ---------------------------------------------------------------------------
# Service definitions: name  gradle-module  port  spring-profile
# Compatible with macOS' default Bash 3.2, so avoid associative arrays.
# ---------------------------------------------------------------------------
declare -a SERVICE_NAMES=(
    "key-generation-service"
    "write-service"
    "redirect-service"
    "analytics-service"
)

service_module() {
    case "$1" in
        key-generation-service|write-service|redirect-service|analytics-service)
            echo "$1"
            ;;
        *)
            error "Unknown service: $1"
            exit 1
            ;;
    esac
}

service_port() {
    case "$1" in
        key-generation-service) echo "${URL_SHORTENER_KEY_GENERATION_SERVICE_PORT}" ;;
        write-service) echo "${URL_SHORTENER_WRITE_SERVICE_PORT}" ;;
        redirect-service) echo "${URL_SHORTENER_REDIRECT_SERVICE_PORT}" ;;
        analytics-service) echo "${URL_SHORTENER_ANALYTICS_SERVICE_PORT}" ;;
        *)
            error "Unknown service: $1"
            exit 1
            ;;
    esac
}

service_profile() {
    case "$1" in
        key-generation-service|write-service|redirect-service|analytics-service)
            echo "dev"
            ;;
        *)
            error "Unknown service: $1"
            exit 1
            ;;
    esac
}

# ---------------------------------------------------------------------------
# Check if a port is already in use
# ---------------------------------------------------------------------------
port_in_use() {
    local port=$1
    lsof -iTCP:"${port}" -sTCP:LISTEN -P -n >/dev/null 2>&1
}

java_major_version() {
    local java_bin="$1"
    local version_output
    version_output="$("${java_bin}" -version 2>&1 | head -n 1)"

    if [[ "${version_output}" =~ \"([0-9]+)\. ]]; then
        echo "${BASH_REMATCH[1]}"
        return 0
    fi

    if [[ "${version_output}" =~ \"([0-9]+)\" ]]; then
        echo "${BASH_REMATCH[1]}"
        return 0
    fi

    return 1
}

resolve_java_bin() {
    local candidate
    local version

    if command -v java >/dev/null 2>&1; then
        candidate="$(command -v java)"
        version="$(java_major_version "${candidate}" || true)"
        if [[ -n "${version}" && "${version}" -ge 21 ]]; then
            echo "${candidate}"
            return 0
        fi
    fi

    if [[ -d "${HOME}/.gradle/jdks" ]]; then
        while IFS= read -r candidate; do
            version="$(java_major_version "${candidate}" || true)"
            if [[ -n "${version}" && "${version}" -ge 21 ]]; then
                echo "${candidate}"
                return 0
            fi
        done < <(find "${HOME}/.gradle/jdks" -type f -path '*/bin/java' 2>/dev/null | sort)
    fi

    error "Java 21+ runtime not found. Install Java 21 or let Gradle provision a JDK under ~/.gradle/jdks."
    exit 1
}

build_service_jars() {
    header "Building Spring Boot service jars"

    cd "${PROJECT_ROOT}"
    ./gradlew \
        :key-generation-service:bootJar \
        :write-service:bootJar \
        :redirect-service:bootJar \
        :analytics-service:bootJar \
        --no-daemon
}

# ---------------------------------------------------------------------------
# Start a single service
# ---------------------------------------------------------------------------
start_service() {
    local name="$1"
    local module
    local port
    local profile
    local log_file="${LOGS_DIR}/${name}.log"
    local pid_file="${LOGS_DIR}/${name}.pid"
    local jar_file

    module="$(service_module "${name}")"
    port="$(service_port "${name}")"
    profile="$(service_profile "${name}")"
    jar_file="${PROJECT_ROOT}/${module}/build/libs/app.jar"

    if port_in_use "${port}"; then
        warn "Port ${port} already in use — ${name} may already be running. Skipping."
        return 0
    fi

    if [[ ! -f "${jar_file}" ]]; then
        error "Boot jar not found for ${name}: ${jar_file}"
        exit 1
    fi

    log "Starting ${BOLD}${name}${NC} on port ${port} (profile: ${profile})..."

    nohup "${JAVA_BIN}" -jar "${jar_file}" \
        --spring.profiles.active="${profile}" \
        > "${log_file}" 2>&1 < /dev/null &

    local pid=$!
    echo "${pid}" > "${pid_file}"
    success "${name} started (PID ${pid}) — log: ${log_file}"
}

# ---------------------------------------------------------------------------
# Wait for a service HTTP endpoint to respond
# ---------------------------------------------------------------------------
wait_for_service() {
    local name="$1"
    local port
    local url
    local timeout="${URL_SHORTENER_SERVICE_START_TIMEOUT_SECONDS}"
    local elapsed=0
    local interval=3

    port="$(service_port "${name}")"
    url="http://localhost:${port}/actuator/health"

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
    JAVA_BIN="${URL_SHORTENER_JAVA_BIN:-$(resolve_java_bin)}"
    log "Java runtime : ${JAVA_BIN}"
    echo ""

    build_service_jars

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
    echo -e "  key-generation-service  ${CYAN}http://localhost:${URL_SHORTENER_KEY_GENERATION_SERVICE_PORT}${NC}      ${LOGS_DIR}/key-generation-service.log"
    echo -e "  write-service           ${CYAN}http://localhost:${URL_SHORTENER_WRITE_SERVICE_PORT}${NC}      ${LOGS_DIR}/write-service.log"
    echo -e "  redirect-service        ${CYAN}http://localhost:${URL_SHORTENER_REDIRECT_SERVICE_PORT}${NC}      ${LOGS_DIR}/redirect-service.log"
    echo -e "  analytics-service       ${CYAN}http://localhost:${URL_SHORTENER_ANALYTICS_SERVICE_PORT}${NC}      ${LOGS_DIR}/analytics-service.log"
    echo ""
    echo -e "  ${BOLD}Actuator health endpoints:${NC}"
    echo -e "    KGS      : ${CYAN}http://localhost:${URL_SHORTENER_KEY_GENERATION_SERVICE_PORT}/actuator/health${NC}"
    echo -e "    Write    : ${CYAN}http://localhost:${URL_SHORTENER_WRITE_SERVICE_PORT}/actuator/health${NC}"
    echo -e "    Redirect : ${CYAN}http://localhost:${URL_SHORTENER_REDIRECT_SERVICE_PORT}/actuator/health${NC}"
    echo -e "    Analytics: ${CYAN}http://localhost:${URL_SHORTENER_ANALYTICS_SERVICE_PORT}/actuator/health${NC}"
    echo ""
    echo -e "  To stop all services: ${YELLOW}./infrastructure/scripts/stop-all.sh${NC}"
    echo ""
}

main "$@"
