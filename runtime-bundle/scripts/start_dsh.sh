#!/bin/bash
# start_dsh.sh - runs inside Debian sandbox. Starts official DSH WebUI.
# Prefers the offline-installed local DSH at /opt/dshapp/runtime; falls back
# to npx only when the local install is absent. Do not hardcode old CLI flags
# if upstream changes them.

set -euo pipefail

export HOME="${HOME:-/root}"
export TERM="${TERM:-xterm-256color}"
# Android ALWAYS exports a host PATH (/system/bin ...), so the "${PATH:-...}"
# default never fires and the guest keeps the host PATH. Node's PATH lookup
# (child_process.spawn('bash'), node-pty's execvp, ...) then cannot find
# /usr/bin/bash and every command tool fails with ENOENT. Force the Debian PATH.
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
# Android kernels expose no Landlock and bubblewrap cannot create user
# namespaces inside the app sandbox, so DSH's own file sandbox has no usable
# backend here and confined modes fail with SANDBOX_UNAVAILABLE. The PRoot
# sandbox IS the boundary; run DSH danger-full-access (the env knob read by
# dsh-base's sandbox-policy row; also sets approval to 'never').
export DSH_PERMISSION_MODE="${DSH_PERMISSION_MODE:-danger-full-access}"

# DSH user data (sessions, settings, credentials, profile installs) MUST live
# in the persistent workspace (/root/projects, bound to the app's user-data),
# never inside the rootfs: runtime bundles are rebuilt and re-extracted, and a
# rootfs-resident DSH_HOME would both leak user data into bundles/APKs and be
# wiped on every runtime update. DSH honors DSH_HOME via dsh-home-paths.
export DSH_HOME="${DSH_HOME:-/root/projects/.dsh}"
mkdir -p "$DSH_HOME"
# DSH's workspace root follows the process cwd (dsh-base sandbox-policy row);
# starting in the persistent workspace makes the DSH workspace identical to
# the app's Files-tab workspace (/root/projects).
cd /root/projects

HOST="${DSH_HOST:-127.0.0.1}"
PORT="${DSH_PORT:-3080}"

DSH_BIN="/opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/lib/bin.js"
NODE_BIN="${NODE_BIN:-/usr/local/bin/node}"

if [ -x "$NODE_BIN" ] && [ -f "$DSH_BIN" ]; then
    exec "$NODE_BIN" "$DSH_BIN" web --host "$HOST" --port "$PORT"
fi

# Fallback (should only be used in development; offline bundles have DSH installed).
exec npx --yes @deepseek-ai/dsh web --host "$HOST" --port "$PORT"
