#!/usr/bin/env bash
# =============================================================================
# run-perf-tests.sh — Execute Gatling performance test simulations
# =============================================================================
# Checks that the target services are reachable, then runs the Gatling
# simulations via Gradle.  On completion, prints the HTML report path.
#
# Usage:
#   ./infrastructure/scripts/run-perf-tests.sh [simulation]
#
# Arguments:
#   simulation   Optional. One of: redirect, write, mixed, bloomfilter, all
#                Default: all  (runs gatlingRun which picks up all simulations)
#
# Environment variables (all optional):
#   BASE_URL     Base URL for redirect-service  (default: http://localhost:8080)
#   WRITE_URL    Base URL for write-service     (default: http://localhost:8082)
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
PERF_MODULE="infrastructure:performance-tests"
REPORT_BASE_DIR="${PROJECT_ROOT}/infrastructure/performance-tests/build/reports/gatling"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
BASE_URL="${BASE_URL:-http://localhost:8080}"
WRITE_URL="${WRITE_URL:-http://localhost:8082}"
SIMULATION="${1:-all}"

# ---------------------------------------------------------------------------
# Map friendly names to Gradle task names
# ---------------------------------------------------------------------------
declare -A SIMULATION_TASK=(
    ["redirect"]="gatlingRun-simulations.RedirectSimulation"
    ["write"]="gatlingRun-simulations.ShortenUrlSimulation"
    ["mixed"]="gatlingRun-simulations.MixedLoadSimulation"
    ["bloomfilter"]="gatlingRun-simulations.BloomFilterRejectionSimulation"
    ["all"]="gatlingRun"
)

# ---------------------------------------------------------------------------
# Pre-flight: check services are reachable
# ---------------------------------------------------------------------------
check_service() {
    local name="$1"
    local url="$2"

    if curl -sf --max-time 3 "${url}" >/dev/null 2>&1; then
        success "${name} is reachable at ${url}"
    else
        error "${name} is NOT reachable at ${url}"
        error "Start the services first: ./infrastructure/scripts/start-services.sh"
        return 1
    fi
}

preflight_checks() {
    header "Pre-flight service checks"

    local all_ok=true

    check_service "redirect-service" "${BASE_URL}/actuator/health"  || all_ok=false
    check_service "write-service"    "${WRITE_URL}/actuator/health" || all_ok=false

    if ! ${all_ok}; then
        error "One or more services are not available. Aborting performance tests."
        exit 1
    fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    echo ""
    echo -e "${BOLD}URL Shortener — Gatling Performance Tests${NC}"
    echo ""
    echo "  Simulation : ${SIMULATION}"
    echo "  Base URL   : ${BASE_URL}"
    echo "  Write URL  : ${WRITE_URL}"
    echo ""

    # Validate simulation name
    if [[ -z "${SIMULATION_TASK[${SIMULATION}]+_}" ]]; then
        error "Unknown simulation: '${SIMULATION}'"
        error "Valid options: redirect, write, mixed, bloomfilter, all"
        exit 1
    fi

    preflight_checks

    # Verify Gradle wrapper exists
    if [[ ! -f "${PROJECT_ROOT}/gradlew" ]]; then
        error "Gradle wrapper not found at ${PROJECT_ROOT}/gradlew"
        exit 1
    fi

    local gradle_task="${SIMULATION_TASK[$SIMULATION]}"
    header "Running Gatling simulation: ${gradle_task}"

    local start_ts
    start_ts=$(date +%s)

    cd "${PROJECT_ROOT}"
    ./gradlew ":${PERF_MODULE}:${gradle_task}" \
        -PbaseUrl="${BASE_URL}" \
        -PwriteUrl="${WRITE_URL}" \
        --no-daemon \
        --info

    local end_ts elapsed
    end_ts=$(date +%s)
    elapsed=$(( end_ts - start_ts ))

    # ---------------------------------------------------------------------------
    # Find and print the latest report directory
    # ---------------------------------------------------------------------------
    header "Performance test complete"
    echo ""
    echo -e "  Duration: ${elapsed}s"
    echo ""

    if [[ -d "${REPORT_BASE_DIR}" ]]; then
        local latest_report
        latest_report=$(ls -td "${REPORT_BASE_DIR}/"*/ 2>/dev/null | head -n1 || true)

        if [[ -n "${latest_report}" ]]; then
            success "Gatling HTML report:"
            echo ""
            echo -e "    ${CYAN}${latest_report}index.html${NC}"
            echo ""

            # Try to open the report automatically on macOS or Linux with a display
            if command -v open >/dev/null 2>&1; then
                log "Opening report in browser..."
                open "${latest_report}index.html" || true
            elif command -v xdg-open >/dev/null 2>&1 && [[ -n "${DISPLAY:-}" ]]; then
                log "Opening report in browser..."
                xdg-open "${latest_report}index.html" || true
            fi
        else
            warn "Report directory exists but no simulation results found inside: ${REPORT_BASE_DIR}"
        fi
    else
        warn "Report directory not found: ${REPORT_BASE_DIR}"
        warn "The simulation may have failed — check the Gradle output above."
    fi

    echo ""
    echo -e "  ${BOLD}All reports:${NC} ${REPORT_BASE_DIR}"
    echo ""
}

main "$@"
