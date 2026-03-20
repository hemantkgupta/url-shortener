#!/usr/bin/env bash
# =============================================================================
# deploy/onebox/scripts/up.sh — Start the shared one-box deployment
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

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

DATA_ROOT="${URL_SHORTENER_DATA_ROOT:-/data/server/url-shortener}"

mkdir -p \
  "${DATA_ROOT}/scylla" \
  "${DATA_ROOT}/redis" \
  "${DATA_ROOT}/etcd" \
  "${DATA_ROOT}/kafka" \
  "${DATA_ROOT}/clickhouse" \
  "${DATA_ROOT}/clickhouse-logs"

bash "${SCRIPT_DIR}/doctor.sh"

exec docker compose \
  --env-file "${ENV_FILE}" \
  -f "${COMPOSE_FILE}" \
  up -d --build "$@"
