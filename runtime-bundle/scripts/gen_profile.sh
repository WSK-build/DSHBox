#!/usr/bin/env bash
# gen_profile.sh - Generate runtime-profile.json from built layer artifacts.
#
# runtime-profile.json is the single source of truth for how the runtime layers
# are assembled at runtime (assembly order, env_file per layer, versions,
# checksums, size). The app reads it (RuntimeProfile.kt) to:
#   - validate each layer's presence + checksum,
#   - assemble the PRoot merged view + per-layer env injection,
#   - run version arbitration for the runtime body layers.
#
# Usage:
#   gen_profile.sh <layers_dir> <out_profile.json> [bundle_version] [arch] [zstd_level]
#
# <layers_dir> must contain base.tar.* , node.tar.* , android-side.tar.*
# (either .tar.zst or .tar.gz) and their .sha256 sidecars.
set -euo pipefail

LAYERS_DIR="${1:?usage: gen_profile.sh <layers_dir> <out_profile.json> [version] [arch] [zstd_level] [node_version]}"
OUT_PROFILE="${2:?usage: gen_profile.sh <layers_dir> <out_profile.json> [version] [arch] [zstd_level] [node_version]}"
VERSION="${3:-0.1.0}"
ARCH="${4:-arm64}"
ZSTD_LEVEL="${5:-19}"
NODE_VERSION="${6:-${NODE_VERSION:-24.19.0}}"

if [ ! -d "$LAYERS_DIR" ]; then
    echo "layers dir not found: $LAYERS_DIR" >&2
    exit 1
fi

layer_hash() {
    # <name>.tar.zst.sha256 (or .tar.gz.sha256) -> first token (the sha256 hex).
    local f="$LAYERS_DIR/$1.tar.zst.sha256"
    [ -f "$f" ] || f="$LAYERS_DIR/$1.tar.gz.sha256"
    [ -f "$f" ] || { echo ""; return 0; }
    awk '{print $1}' "$f"
}
layer_size() {
    local f="$LAYERS_DIR/$1.tar.zst"
    [ -f "$f" ] || f="$LAYERS_DIR/$1.tar.gz"
    [ -f "$f" ] || { echo "0"; return 0; }
    stat -c%s "$f"
}
layer_compression() {
    [ -f "$LAYERS_DIR/$1.tar.zst" ] && echo "zstd" || echo "gzip"
}
layer_file() {
    # actual artifact basename (compression NAME (.zst) vs extension (.gz)).
    [ -f "$LAYERS_DIR/$1.tar.zst" ] && echo "$1.tar.zst" || echo "$1.tar.gz"
}

BASE_HASH="$(layer_hash base)"
NODE_HASH="$(layer_hash node)"
ASIDE_HASH="$(layer_hash android-side)"
BASE_SIZE="$(layer_size base)"
NODE_SIZE="$(layer_size node)"
ASIDE_SIZE="$(layer_size android-side)"
BASE_Z="$(layer_compression base)"
NODE_Z="$(layer_compression node)"
ASIDE_Z="$(layer_compression android-side)"

[ -n "$BASE_HASH" ] || { echo "missing base layer hash" >&2; exit 1; }
[ -n "$NODE_HASH" ] || { echo "missing node layer hash" >&2; exit 1; }
[ -n "$ASIDE_HASH" ] || { echo "missing android-side layer hash" >&2; exit 1; }

mkdir -p "$(dirname "$OUT_PROFILE")"
cat > "$OUT_PROFILE" <<JSON
{
  "bundle": {
    "kind": "runtime",
    "name": "dshapp-runtime-debian-${ARCH}",
    "version": "${VERSION}",
    "arch": "${ARCH}",
    "compression": "zstd",
    "zstd_level": ${ZSTD_LEVEL},
    "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  },
  "layers": [
    {
      "name": "base",
      "version": "${VERSION}",
      "compression": "${BASE_Z}",
      "sha256": "${BASE_HASH}",
      "size_bytes": ${BASE_SIZE},
      "env_file": ".dshbox/env.d/base.sh",
      "file": "$(layer_file base)",
      "deps": []
    },
    {
      "name": "node",
      "version": "${NODE_VERSION}",
      "compression": "${NODE_Z}",
      "sha256": "${NODE_HASH}",
      "size_bytes": ${NODE_SIZE},
      "env_file": ".dshbox/env.d/node.sh",
      "file": "$(layer_file node)",
      "deps": ["base"]
    },
    {
      "name": "android-side",
      "version": "${VERSION}",
      "compression": "${ASIDE_Z}",
      "sha256": "${ASIDE_HASH}",
      "size_bytes": ${ASIDE_SIZE},
      "env_file": ".dshbox/env.d/android-side.sh",
      "file": "$(layer_file android-side)",
      "deps": ["base"]
    }
  ],
  "assembly": ["base", "node", "android-side"]
}
JSON

echo "runtime-profile.json written to $OUT_PROFILE"
