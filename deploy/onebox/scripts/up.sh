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
FORCE_BUILD=0
COMPOSE_SERVICES=()
BUILDABLE_SERVICES=(
  "key-generation-service"
  "write-service"
  "redirect-service"
  "analytics-service"
  "gateway"
)

for arg in "$@"; do
  if [[ "${arg}" == "--build" ]]; then
    FORCE_BUILD=1
  else
    COMPOSE_SERVICES+=("${arg}")
  fi
done

DATA_DIRS=(
  "${DATA_ROOT}/scylla"
  "${DATA_ROOT}/redis"
  "${DATA_ROOT}/etcd"
  "${DATA_ROOT}/kafka"
  "${DATA_ROOT}/clickhouse"
  "${DATA_ROOT}/clickhouse-logs"
)

mkdir -p "${DATA_DIRS[@]}"

# One-box bind mounts are shared across multiple images with different runtime UIDs.
# Relax permissions so services can initialize their own on-disk state without host-specific UID tuning.
CHMOD_CMD=(chmod -R a+rwX)
if [[ "${EUID}" -ne 0 ]] && command -v sudo >/dev/null 2>&1; then
  CHMOD_CMD=(sudo chmod -R a+rwX)
fi
"${CHMOD_CMD[@]}" "${DATA_DIRS[@]}"

bash "${SCRIPT_DIR}/doctor.sh"

NEEDS_BUILD="${FORCE_BUILD}"
if [[ "${NEEDS_BUILD}" -eq 0 ]]; then
  TARGET_SERVICES=("${COMPOSE_SERVICES[@]}")
  if [[ ${#TARGET_SERVICES[@]} -eq 0 ]]; then
    TARGET_SERVICES=("${BUILDABLE_SERVICES[@]}")
  fi

  for service in "${TARGET_SERVICES[@]}"; do
    case " ${BUILDABLE_SERVICES[*]} " in
      *" ${service} "*)
        if ! docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" images -q "${service}" | grep -q '[^[:space:]]'; then
          NEEDS_BUILD=1
          break
        fi
        ;;
    esac
  done
fi

UP_ARGS=(-d)
if [[ "${NEEDS_BUILD}" -eq 1 ]]; then
  echo "Building updated or missing images before startup..."
  UP_ARGS+=(--build)
else
  echo "Reusing cached images. Pass --build to rebuild application images."
fi

exec docker compose \
  --env-file "${ENV_FILE}" \
  -f "${COMPOSE_FILE}" \
  up "${UP_ARGS[@]}" "${COMPOSE_SERVICES[@]}"
