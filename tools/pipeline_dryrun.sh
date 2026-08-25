#!/usr/bin/env bash
# pipeline_dryrun.sh - Pre-flight check + dry-run of the layered runtime bundle
# pipeline (Phase D gate). MUST run in WSL2/Linux.
#
# It does NOT build anything. It verifies the host toolchain and prints the
# exact steps build_arm64_runtime_bundle.sh will take, so problems are found
# before a long debootstrap run. Run this FIRST after entering WSL2.
#
# Usage:   tools/pipeline_dryrun.sh
# Exit 0  = prerequisites OK;   Exit 1 = a hard prerequisite is missing.
set -u

fatal() { echo "[FAIL] $1" >&2; exit 1; }
warn()  { echo "[warn] $1" >&2; }
ok()    { echo "[ok]   $1"; }

echo "== pipeline dry-run: prerequisites =="

# Hard prerequisites (missing -> abort).
for tool in debootstrap qemu-user-static; do
    if command -v "$tool" >/dev/null 2>&1; then ok "found: $tool"; else fatal "missing: $tool (sudo apt-get install $tool)"; fi
done
command -v tar >/dev/null 2>&1 && ok "found: tar" || fatal "missing: tar"

# Compression: zstd preferred (>= level 19), degrade to gzip with a recorded reason.
if command -v zstd >/dev/null 2>&1; then
    ok "found: zstd ($(zstd --version 2>/dev/null | head -1))"
else
    warn "zstd NOT found -> layers will pack as gzip per degrade policy (see CHANGELOG/Pack section)"
fi

# Network is required for debootstrap/apt and the Node download.
if command -v apt-get >/dev/null 2>&1; then
    ok "found: apt-get"
else
    warn "apt-get NOT found; cannot debootstrap from mirror"
fi

# Node: must be 24.19.0 to match the bundle's node layer.
NODE_VERSION="${NODE_VERSION:-24.19.0}"
if command -v node >/dev/null 2>&1; then
    hv="$(node --version | sed 's/^v//')"
    if [ "$hv" = "$NODE_VERSION" ]; then ok "node $hv == $NODE_VERSION"; else warn "node $hv != $NODE_VERSION (bundle node layer will be $NODE_VERSION)"; fi
else
    warn "node CLI not found on host; the node layer is downloaded by build_node.sh"
fi

# Cross-arch: user-mode emulation for the foreign arch build.
if command -v qemu-aarch64-static >/dev/null 2>&1; then ok "found: qemu-aarch64-static"; else warn "qemu-aarch64-static not on PATH (qemu-user-static must provide it for arm64)"; fi

echo
echo "== pipeline dry-run: steps build_arm64_runtime_bundle.sh will run =="
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
for step in \
    "L0 base:        $ROOT_DIR/runtime-bundle/build_base.sh trixie arm64" \
    "L1 node:        $ROOT_DIR/runtime-bundle/build_node.sh (NODE_VERSION=$NODE_VERSION)" \
    "L3 android-side:$ROOT_DIR/runtime-bundle/build_android_side.sh" \
    "pack each:      $ROOT_DIR/tools/pack_runtime.sh <layer> <src> <layers> <ver> <arch> <zstd_level>" \
    "profile:        $ROOT_DIR/runtime-bundle/scripts/gen_profile.sh <layers> <out> <ver> <arch> <zstd> <nodever>" ; do
    echo "  - $step"
done
echo
echo "NOTE: the pipeline packs base/node/android-side ONLY. DSH is a separate product and is NOT in the bundle."
echo "dry-run complete."
