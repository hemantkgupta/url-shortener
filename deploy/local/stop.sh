#!/usr/bin/env bash
# =============================================================================
# deploy/local/stop.sh — Stop the local URL shortener stack
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

exec bash "${PROJECT_ROOT}/infrastructure/scripts/stop-all.sh" "$@"
