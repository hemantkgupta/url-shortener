#!/usr/bin/env bash
# Compatibility wrapper. Canonical local entrypoint lives at
# /deploy/local/run.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec bash "${SCRIPT_DIR}/deploy/local/run.sh" "$@"
