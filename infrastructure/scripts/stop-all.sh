#!/usr/bin/env bash
# =============================================================================
# stop-all.sh — Stop all Docker Compose infrastructure and Spring Boot services
# =============================================================================
# Stops:
#   1. Local application services (Spring Boot services + frontend)
#   2. Docker Compose stack (infrastructure/compose/docker-compose.dev.yml)
#
# Usage:
#   ./infrastructure/scripts/stop-all.sh [--infra-only | --services-only]
#
# Flags:
#   --infra-only      Stop only Docker Compose services
#   --services-only   Stop only Spring Boot processes
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
COMPOSE_FILE="${PROJECT_ROOT}/infrastructure/compose/docker-compose.dev.yml"
LOGS_DIR="${PROJECT_ROOT}/logs"
LOCAL_ENV_FILE="${HOME}/.url-shortener-local-dev"

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

# ---------------------------------------------------------------------------
# Parse flags
# ---------------------------------------------------------------------------
STOP_INFRA=true
STOP_SERVICES=true

for arg in "$@"; do
    case "${arg}" in
        --infra-only)
            STOP_SERVICES=false
            ;;
        --services-only)
            STOP_INFRA=false
            ;;
        --help|-h)
            echo "Usage: $0 [--infra-only | --services-only]"
            exit 0
            ;;
        *)
            warn "Unknown flag: ${arg} (ignored)"
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Stop local application services
# ---------------------------------------------------------------------------
# Compatible with macOS' default Bash 3.2, so avoid associative arrays.
declare -a SERVICE_NAMES=(
    "key-generation-service"
    "write-service"
    "redirect-service"
    "analytics-service"
    "frontend"
)

service_port() {
    case "$1" in
        key-generation-service) echo "${URL_SHORTENER_KEY_GENERATION_SERVICE_PORT}" ;;
        write-service) echo "${URL_SHORTENER_WRITE_SERVICE_PORT}" ;;
        redirect-service) echo "${URL_SHORTENER_REDIRECT_SERVICE_PORT}" ;;
        analytics-service) echo "${URL_SHORTENER_ANALYTICS_SERVICE_PORT}" ;;
        frontend) echo "${URL_SHORTENER_FRONTEND_PORT}" ;;
        *)
            error "Unknown service: $1"
            exit 1
            ;;
    esac
}

process_command() {
    ps -p "$1" -o command= 2>/dev/null || true
}

is_expected_service_process() {
    local name="$1"
    local pid="$2"
    local cmd

    cmd="$(process_command "${pid}")"
    if [[ -z "${cmd}" ]]; then
        return 1
    fi

    case "${name}" in
        frontend)
            [[ "${cmd}" == *node* || "${cmd}" == *npm* || "${cmd}" == *vite* ]]
            ;;
        key-generation-service|write-service|redirect-service|analytics-service)
            if [[ "${cmd}" == *com.docker* || "${cmd}" == *vpnkit* || "${cmd}" == *containerd* || "${cmd}" == *docker* ]]; then
                return 1
            fi
            [[ "${cmd}" == *java* || "${cmd}" == *gradle* || "${cmd}" == *Gradle* ]]
            ;;
        *)
            return 1
            ;;
    esac
}

kill_process() {
    local pid=$1
    local name=$2
    if kill -0 "${pid}" 2>/dev/null; then
        log "Sending SIGTERM to ${name} (PID ${pid})..."
        kill -TERM "${pid}" 2>/dev/null || true
        # Wait up to 15s for graceful shutdown
        local waited=0
        while kill -0 "${pid}" 2>/dev/null && (( waited < 15 )); do
            sleep 1
            (( waited++ ))
        done
        if kill -0 "${pid}" 2>/dev/null; then
            warn "Process ${pid} (${name}) did not exit — sending SIGKILL"
            kill -KILL "${pid}" 2>/dev/null || true
        fi
        success "Stopped ${name} (PID ${pid})"
    else
        log "Process ${pid} for ${name} is not running (already stopped)"
    fi
}

stop_local_services() {
    header "Stopping local application services"

    for name in "${SERVICE_NAMES[@]}"; do
        local pid_file="${LOGS_DIR}/${name}.pid"
        local port

        port="$(service_port "${name}")"

        # Try PID file first
        if [[ -f "${pid_file}" ]]; then
            local pid
            pid=$(cat "${pid_file}")
            if is_expected_service_process "${name}" "${pid}"; then
                kill_process "${pid}" "${name}"
            else
                warn "PID file for ${name} points to PID ${pid}, but it does not look like this local service. Skipping kill."
            fi
            rm -f "${pid_file}"
        else
            # Fall back to finding process by port
            local port_pid
            port_pid=$(lsof -iTCP:"${port}" -sTCP:LISTEN -P -n -t 2>/dev/null || true)
            if [[ -n "${port_pid}" ]]; then
                if is_expected_service_process "${name}" "${port_pid}"; then
                    warn "No PID file for ${name} — found PID ${port_pid} on port ${port}"
                    kill_process "${port_pid}" "${name}"
                else
                    warn "Port ${port} is in use by PID ${port_pid}, but it does not look like the local ${name}. Skipping kill."
                fi
            else
                log "${name} does not appear to be running on port ${port}"
            fi
        fi
    done

    # Also kill any lingering Gradle daemons that may hold ports open
    log "Stopping Gradle daemons (if any)..."
    if [[ -f "${PROJECT_ROOT}/gradlew" ]]; then
        (cd "${PROJECT_ROOT}" && ./gradlew --stop 2>/dev/null) || true
    fi
    success "Local application services stopped"
}

# ---------------------------------------------------------------------------
# Stop Docker Compose stack
# ---------------------------------------------------------------------------
stop_docker_infra() {
    header "Stopping Docker Compose infrastructure"

    if [[ ! -f "${COMPOSE_FILE}" ]]; then
        warn "Compose file not found: ${COMPOSE_FILE}"
        return 0
    fi

    if ! docker info >/dev/null 2>&1; then
        warn "Docker daemon is not running — skipping compose down"
        return 0
    fi

    log "Running: docker compose -f ${COMPOSE_FILE} down"
    docker compose -f "${COMPOSE_FILE}" down --remove-orphans
    success "Docker Compose stack stopped"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    echo ""
    echo -e "${BOLD}URL Shortener — Stopping all components${NC}"
    echo ""

    if ${STOP_SERVICES}; then
        stop_local_services
    fi

    if ${STOP_INFRA}; then
        stop_docker_infra
    fi

    header "All stopped"
    echo ""
    echo -e "  To restart infrastructure : ${CYAN}./infrastructure/scripts/start-infra.sh${NC}"
    echo -e "  To restart services       : ${CYAN}./infrastructure/scripts/start-services.sh${NC}"
    echo ""
}

main "$@"
