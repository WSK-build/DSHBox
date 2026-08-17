#!/bin/bash
# healthcheck.sh - runs on Android or inside sandbox to probe DSH readiness.
# A plain HTTP probe on 127.0.0.1; SandboxManager decides Ready based on
# sandbox running -> DSH process -> port open -> HTTP probe -> WebUI ready.

set -euo pipefail

HOST="${DSH_HOST:-127.0.0.1}"
PORT="${DSH_PORT:-3080}"
TIMEOUT="${HEALTHCHECK_TIMEOUT_SECONDS:-5}"

if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time "$TIMEOUT" "http://$HOST:$PORT/" >/dev/null
    exit 0
fi

if command -v wget >/dev/null 2>&1; then
    wget -q -T "$TIMEOUT" -O /dev/null "http://$HOST:$PORT/"
    exit 0
fi

echo "no http client available for healthcheck" >&2
exit 1
