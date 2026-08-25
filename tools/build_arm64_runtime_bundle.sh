#!/usr/bin/env bash
# build_arm64_runtime_bundle.sh - SCHEDULER for the layered runtime bundle.
#
# The old monolithic "Debian rootfs + Node + DSH" single tarball is gone. This
# script now orchestrates the three runtime layers (base/node/android-side),
# packs each into a zstd layer tarball, and emits runtime-profile.json. DSH is
# NOT built here anymore — it is a separate product (see app DSH update flow).
#
# Build MUST run in WSL2/Linux (debootstrap + qemu-user-static; Windows Docker
# is forbidden by project policy, see build_rootfs.sh:57). Always run after
# confirming those tools are present (see pipeline_dryrun.sh, Phase D gate).
#
# Usage:
#   tools/build_arm64_runtime_bundle.sh [suite] [arch]
#
# Environment:
#   NODE_VERSION  node layer version (default 24.19.0)
#   BUNDLE_VERSION bundle version (default 0.1.0)
#   OUT_DIR       dist output dir (default $ROOT/build/dist)
#   ZSTD_LEVEL    zstd compression level (default 19)
set -euo pipefail

SUITE="${1:-trixie}"
ARCH="${2:-arm64}"
BUNDLE_VERSION="${BUNDLE_VERSION:-0.1.0}"
NODE_VERSION="${NODE_VERSION:-24.19.0}"
ZSTD_LEVEL="${ZSTD_LEVEL:-19}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/build}"
OUT_DIR="${OUT_DIR:-$BUILD_DIR/dist}"
LAYERS_DIR="$BUILD_DIR/layers"

if ! command -v debootstrap >/dev/null 2>&1; then
    echo "missing debootstrap; install: sudo apt-get install debootstrap qemu-user-static" >&2
    exit 1
fi
if ! command -v zstd >/dev/null 2>&1; then
    echo "WARNING: zstd CLI not found; layers will be packed as gzip (see pack_runtime.sh)" >&2
fi

mkdir -p "$LAYERS_DIR" "$OUT_DIR"

echo "==================================================================="
echo " RUNTIME BUNDLE (layered, no DSH)  suite=$SUITE arch=$ARCH"
echo " version=$BUNDLE_VERSION node=$NODE_VERSION zstd_level=$ZSTD_LEVEL"
echo "==================================================================="

echo "==> L0 base"
BASE_DIR="$LAYERS_DIR/base-src"
rm -rf "$BASE_DIR"
# Reuse build_base.sh (writes into BUILD_DIR/rootfs/<suite>-<arch>); then copy
# its output into a clean layer staging dir rooted at the layer name.
BUILD_DIR="$BUILD_DIR" OUT_DIR="$BASE_DIR" \
    bash "$ROOT_DIR/runtime-bundle/build_base.sh" "$SUITE" "$ARCH"
# build_base.sh stages a full rootfs; the layer content is that rootfs.
rm -rf "$BASE_DIR"; mkdir -p "$BASE_DIR"
cp -a "$BUILD_DIR/rootfs/${SUITE}-${ARCH}/." "$BASE_DIR/"

# Layer env self-declaration.
mkdir -p "$BASE_DIR/.dshbox/env.d"
cat > "$BASE_DIR/.dshbox/env.d/base.sh" <<'EOF'
# L0 base (Debian guest env)
export HOME="@HOME@"
export TERM="@TERM@"
export LANG="C.UTF-8"
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export DSH_PERMISSION_MODE="@DSH_PERMISSION_MODE@"
EOF

echo "==> L1 node"
NODE_DIR="$LAYERS_DIR/node-src"
NODE_VERSION="$NODE_VERSION" BUILD_DIR="$BUILD_DIR" OUT_DIR="$NODE_DIR" \
    bash "$ROOT_DIR/runtime-bundle/build_node.sh"
mkdir -p "$NODE_DIR/.dshbox/env.d"
cat > "$NODE_DIR/.dshbox/env.d/node.sh" <<'EOF'
# L1 node (Node runtime is bound at /usr/local in the guest)
export NODE_BIN="@NODE_BIN@"
EOF

echo "==> L3 android-side"
ASIDE_DIR="$LAYERS_DIR/android-side-src"
BUILD_DIR="$BUILD_DIR" OUT_DIR="$ASIDE_DIR" \
    bash "$ROOT_DIR/runtime-bundle/build_android_side.sh"
mkdir -p "$ASIDE_DIR/.dshbox/env.d"
cat > "$ASIDE_DIR/.dshbox/env.d/android-side.sh" <<'EOF'
# L3 android-side (host-side proot env)
export LD_LIBRARY_PATH="@PROOT_LIB@"
export PROOT_LOADER="@PROOT_LOADER@"
export PROOT_TMP_DIR="@PROOT_TMP_DIR@"
EOF

echo "==> pack layers (zstd level $ZSTD_LEVEL)"
BASE_META="$(bash "$ROOT_DIR/tools/pack_runtime.sh" base "$BASE_DIR" "$LAYERS_DIR" "$BUNDLE_VERSION" "$ARCH" "$ZSTD_LEVEL")"
NODE_META="$(bash "$ROOT_DIR/tools/pack_runtime.sh" node "$NODE_DIR" "$LAYERS_DIR" "$NODE_VERSION" "$ARCH" "$ZSTD_LEVEL")"
ASIDE_META="$(bash "$ROOT_DIR/tools/pack_runtime.sh" android-side "$ASIDE_DIR" "$LAYERS_DIR" "$BUNDLE_VERSION" "$ARCH" "$ZSTD_LEVEL")"

echo "==> generate runtime-profile.json"
bash "$ROOT_DIR/runtime-bundle/scripts/gen_profile.sh" \
    "$LAYERS_DIR" "$OUT_DIR/runtime-profile.json" "$BUNDLE_VERSION" "$ARCH" "$ZSTD_LEVEL" "$NODE_VERSION"

echo "==> copy layers to dist"
cp "$LAYERS_DIR"/base.tar.* "$OUT_DIR/"
cp "$LAYERS_DIR"/node.tar.* "$OUT_DIR/"
cp "$LAYERS_DIR"/android-side.tar.* "$OUT_DIR/"

echo "==================================================================="
echo " RUNTIME BUNDLE BUILT (no DSH)"
echo "  layers: base / node / android-side"
echo "  dist:   $OUT_DIR"
echo "  profile: $OUT_DIR/runtime-profile.json"
echo "  NOTE: DSH is a SEPARATE product; it is not in this bundle."
echo "==================================================================="
