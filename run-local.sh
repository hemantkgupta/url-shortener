#!/usr/bin/env bash
# =============================================================================
# run-local.sh — Start the full local URL shortener dev stack on macOS/Linux
# =============================================================================
# What it does:
#   1. Verifies local prerequisites (Docker, Java, Node.js, npm)
#   2. Stops any existing local app processes on the app ports
#   3. Starts core infrastructure dependencies (without SigNoz)
#   4. Initialises ScyllaDB schema
#   5. Starts all Spring Boot services
#   6. Installs frontend dependencies if needed
#   7. Starts the frontend dev server in the background
#   8. Optionally opens the app in the browser
#
# Usage:
#   bash ./run-local.sh
#   bash ./run-local.sh --backend-only
#   bash ./run-local.sh --no-browser
#   bash ./run-local.sh --npm-install
# =============================================================================
set -euo pipefail

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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}"
LOGS_DIR="${PROJECT_ROOT}/logs"
FRONTEND_DIR="${PROJECT_ROOT}/frontend"
FRONTEND_LOG="${LOGS_DIR}/frontend.log"
FRONTEND_PID_FILE="${LOGS_DIR}/frontend.pid"
LOCAL_ENV_FILE="${HOME}/.url-shortener-local-dev"
OPEN_BROWSER=true
START_FRONTEND=true
FORCE_NPM_INSTALL=false

usage() {
  cat <<'EOF'
Usage: ./run-local.sh [options]

Options:
  --backend-only  Start infra and backend services only; skip the frontend dev server.
  --no-browser    Do not open the app in the browser after startup.
  --npm-install   Run `npm install` in frontend before starting the dev server.
  --help, -h      Show this help text.
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --backend-only)
      START_FRONTEND=false
      OPEN_BROWSER=false
      ;;
    --no-browser)
      OPEN_BROWSER=false
      ;;
    --npm-install)
      FORCE_NPM_INSTALL=true
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      warn "Unknown flag: ${arg} (ignored)"
      ;;
  esac
done

mkdir -p "${LOGS_DIR}"

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
export KGS_BASE_URL="${KGS_BASE_URL:-http://localhost:${URL_SHORTENER_KEY_GENERATION_SERVICE_PORT}}"
export OWN_DOMAIN="${OWN_DOMAIN:-localhost:${URL_SHORTENER_FRONTEND_PORT}/r}"

check_command() {
  local command_name="$1"
  local install_hint="$2"
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    error "Missing required command: ${command_name}. ${install_hint}"
    exit 1
  fi
}

check_docker() {
  if ! docker info >/dev/null 2>&1; then
    error "Docker daemon is not running. Start Docker Desktop and retry."
    exit 1
  fi
}

listener_pid_for_port() {
  lsof -iTCP:"$1" -sTCP:LISTEN -P -n -t 2>/dev/null || true
}

process_command() {
  ps -p "$1" -o command= 2>/dev/null || true
}

ensure_port_free() {
  local port="$1"
  local label="$2"
  local pid
  local cmd

  pid="$(listener_pid_for_port "${port}")"
  if [[ -z "${pid}" ]]; then
    return 0
  fi

  cmd="$(process_command "${pid}")"
  error "Port ${port} is still in use by PID ${pid} (${label})."
  if [[ -n "${cmd}" ]]; then
    error "Listener command: ${cmd}"
  fi
  exit 1
}

ensure_required_ports_available() {
  header "Checking required local ports"
  ensure_port_free "${URL_SHORTENER_KEY_GENERATION_SERVICE_PORT}" "key-generation-service"
  ensure_port_free "${URL_SHORTENER_WRITE_SERVICE_PORT}" "write-service"
  ensure_port_free "${URL_SHORTENER_REDIRECT_SERVICE_PORT}" "redirect-service"
  ensure_port_free "${URL_SHORTENER_ANALYTICS_SERVICE_PORT}" "analytics-service"
  if [[ "${START_FRONTEND}" == "true" ]]; then
    ensure_port_free "${URL_SHORTENER_FRONTEND_PORT}" "frontend"
  fi
  success "Required local ports are free"
}

install_frontend_deps_if_needed() {
  if [[ "${START_FRONTEND}" != "true" ]]; then
    return 0
  fi

  if [[ "${FORCE_NPM_INSTALL}" == "true" || ! -d "${FRONTEND_DIR}/node_modules" ]]; then
    header "Installing frontend dependencies"
    (cd "${FRONTEND_DIR}" && npm install)
  else
    log "Frontend dependencies already present"
  fi
}

start_frontend() {
  if [[ "${START_FRONTEND}" != "true" ]]; then
    return 0
  fi

  header "Starting frontend dev server"
  (
    cd "${FRONTEND_DIR}"
    npm run dev
  ) > "${FRONTEND_LOG}" 2>&1 &

  local pid=$!
  echo "${pid}" > "${FRONTEND_PID_FILE}"
  success "Frontend started (PID ${pid}) — log: ${FRONTEND_LOG}"
}

wait_for_frontend() {
  if [[ "${START_FRONTEND}" != "true" ]]; then
    return 0
  fi

  local url="http://localhost:${URL_SHORTENER_FRONTEND_PORT}"
  local timeout=60
  local elapsed=0
  local interval=2

  log "Waiting for frontend to become ready at ${url}..."
  while ! curl -sf --max-time 2 "${url}" >/dev/null 2>&1; do
    if (( elapsed >= timeout )); then
      warn "Timed out waiting for frontend (${timeout}s). Check: ${FRONTEND_LOG}"
      return 1
    fi
    sleep "${interval}"
    (( elapsed += interval ))
    printf "  Waiting for frontend... (%ds/%ds)\r" "${elapsed}" "${timeout}"
  done
  success "Frontend is ready"
}

open_browser_if_requested() {
  if [[ "${OPEN_BROWSER}" != "true" ]]; then
    return 0
  fi

  if command -v open >/dev/null 2>&1; then
    open "http://localhost:${URL_SHORTENER_FRONTEND_PORT}" >/dev/null 2>&1 || true
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "http://localhost:${URL_SHORTENER_FRONTEND_PORT}" >/dev/null 2>&1 || true
  fi
}

print_summary() {
  header "Local stack is ready"
  echo ""
  echo -e "  ${BOLD}App URLs${NC}"
  if [[ "${START_FRONTEND}" == "true" ]]; then
    echo -e "    Frontend : ${CYAN}http://localhost:${URL_SHORTENER_FRONTEND_PORT}${NC}"
    echo -e "    Dashboard: ${CYAN}http://localhost:${URL_SHORTENER_FRONTEND_PORT}/dashboard${NC}"
  else
    echo -e "    Frontend : ${YELLOW}not started (--backend-only)${NC}"
  fi
  echo -e "    Redirect : ${CYAN}http://localhost:${URL_SHORTENER_REDIRECT_SERVICE_PORT}/<shortKey>${NC}"
  echo ""
  echo -e "  ${BOLD}Logs${NC}"
  echo -e "    Backend  : ${CYAN}${LOGS_DIR}${NC}"
  if [[ "${START_FRONTEND}" == "true" ]]; then
    echo -e "    Frontend : ${CYAN}${FRONTEND_LOG}${NC}"
  fi
  echo ""
  echo -e "  ${BOLD}Stop everything${NC}"
  echo -e "    ${YELLOW}./infrastructure/scripts/stop-all.sh${NC}"
  echo ""
}

main() {
  header "URL Shortener — Run Local"

  check_command docker "Install Docker Desktop first."
  check_command java "Install Java 21+ first."
  check_command node "Install Node.js 20+ first."
  check_command npm "Install npm first."
  check_command curl "Install curl first."
  check_command lsof "Install lsof first."
  check_docker

  log "Project root : ${PROJECT_ROOT}"
  log "Log directory: ${LOGS_DIR}"

  header "Stopping existing local app processes"
  bash "${PROJECT_ROOT}/infrastructure/scripts/stop-all.sh" --services-only
  ensure_required_ports_available

  header "Starting core infrastructure"
  bash "${PROJECT_ROOT}/infrastructure/scripts/start-infra.sh" --core-only

  header "Initialising ScyllaDB schema"
  bash "${PROJECT_ROOT}/infrastructure/scripts/init-scylla.sh"

  header "Starting Spring Boot services"
  bash "${PROJECT_ROOT}/infrastructure/scripts/start-services.sh"

  install_frontend_deps_if_needed
  start_frontend
  wait_for_frontend || true
  open_browser_if_requested
  print_summary
}

main "$@"
