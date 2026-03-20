#!/usr/bin/env bash
# =============================================================================
# deploy/onebox/scripts/logs.sh — Tail logs for the one-box deployment
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ONEBOX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${ONEBOX_DIR}/compose.yml"
ENV_FILE="${ONEBOX_DIR}/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}. Copy from ${ONEBOX_DIR}/.env.example first." >&2
  exit 1
fi

exec docker compose \
  --env-file "${ENV_FILE}" \
  -f "${COMPOSE_FILE}" \
  logs -f "$@"
