#!/usr/bin/env bash
# =============================================================================
# deploy/onebox/scripts/doctor.sh — Host readiness checks for one-box deploy
# =============================================================================
set -euo pipefail

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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ONEBOX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${ONEBOX_DIR}/.env"

failures=0

check_command() {
  local name="$1"
  if command -v "${name}" >/dev/null 2>&1; then
    success "Found command: ${name}"
  else
    error "Missing command: ${name}"
    failures=$((failures + 1))
  fi
}

check_compose() {
  if docker compose version >/dev/null 2>&1; then
    success "Docker Compose plugin is available"
  elif command -v docker-compose >/dev/null 2>&1; then
    success "docker-compose is available"
  else
    error "Missing Docker Compose (`docker compose` or `docker-compose`)"
    failures=$((failures + 1))
  fi
}

check_docker() {
  if docker info >/dev/null 2>&1; then
    success "Docker daemon is reachable"
  else
    error "Docker daemon is not reachable"
    failures=$((failures + 1))
  fi
}

check_arch() {
  local arch
  arch="$(uname -m)"
  log "Host architecture: ${arch}"
  if [[ "${arch}" == "aarch64" || "${arch}" == "arm64" ]]; then
    warn "ARM64 host detected. Validate ScyllaDB image support on this host early."
  fi
}

check_memory() {
  local mem_mb=""
  if command -v free >/dev/null 2>&1; then
    mem_mb="$(free -m | awk '/^Mem:/ {print $2}')"
  elif command -v vm_stat >/dev/null 2>&1; then
    mem_mb="$(
      vm_stat | awk '
        /page size of/ {size=$8}
        /Pages free/ {free=$3}
        /Pages active/ {active=$3}
        /Pages inactive/ {inactive=$3}
        /Pages speculative/ {spec=$3}
        /Pages wired down/ {wired=$4}
        END {
          gsub("\\.","",free); gsub("\\.","",active); gsub("\\.","",inactive);
          gsub("\\.","",spec); gsub("\\.","",wired);
          total=(free+active+inactive+spec+wired)*size/1024/1024;
          printf "%.0f", total;
        }'
    )"
  fi

  if [[ -n "${mem_mb}" ]]; then
    log "Detected memory: ${mem_mb} MB"
    if (( mem_mb < 16384 )); then
      warn "Less than 16 GB RAM detected. Full one-box stack may be unstable."
    else
      success "Memory looks sufficient for first-pass one-box experiments"
    fi
  else
    warn "Could not determine host memory"
  fi
}

check_disk() {
  local data_root="${URL_SHORTENER_DATA_ROOT:-/data/server/url-shortener}"
  local parent_dir
  parent_dir="$(dirname "${data_root}")"

  if [[ -d "${data_root}" ]]; then
    success "Data root exists: ${data_root}"
  elif [[ -d "${parent_dir}" ]]; then
    warn "Data root does not exist yet: ${data_root}"
    log "Parent directory exists: ${parent_dir}"
  else
    warn "Neither data root nor parent directory exists yet: ${data_root}"
  fi
}

check_ports() {
  local ports=(80 443)
  local in_use=false
  for port in "${ports[@]}"; do
    if lsof -iTCP:"${port}" -sTCP:LISTEN -P -n >/dev/null 2>&1; then
      warn "Port ${port} is already in use"
      in_use=true
    fi
  done

  if [[ "${in_use}" == "false" ]]; then
    success "Ports 80 and 443 are available"
  fi
}

load_env_if_present() {
  if [[ -f "${ENV_FILE}" ]]; then
    log "Loading ${ENV_FILE}"
    set -a
    # shellcheck disable=SC1090
    source "${ENV_FILE}"
    set +a
  else
    warn "Missing ${ENV_FILE}. Copy from .env.example before deployment."
  fi
}

main() {
  echo -e "${BOLD}URL Shortener One-Box Doctor${NC}"
  load_env_if_present

  check_command docker
  check_command git
  check_command curl
  check_command lsof
  check_compose
  check_docker
  check_arch
  check_memory
  check_disk
  check_ports

  if (( failures > 0 )); then
    error "Doctor found ${failures} blocking issue(s)"
    exit 1
  fi

  success "Doctor checks completed"
}

main "$@"
