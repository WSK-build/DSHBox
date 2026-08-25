#!/usr/bin/env bash
# build_node.sh - Build the L1 "node" layer (Node.js runtime for arm64).
#
# Node is a DISTRIBUTION layer: it is shipped in the runtime bundle separately
# from the Debian base and assembled onto the guest rootfs at runtime (bound at
# /usr/local) instead of being baked into the rootfs. This is what lets the
# running environment upgrade the Node runtime independently of base.
#
# Build runs in WSL2/Linux (must be able to download the Node tarball and
# produce an arm64 extract; no cross-compile happens here for Node itself).
#
# Usage:
#   runtime-bundle/build_node.sh
#
# Environment:
#   NODE_VERSION  default "24.19.0" (target version; Phase D validates the full
#                 arm64 native-package recompile against this exact version)
#   ARCH          default "arm64"
#   BUILD_DIR     build work root (default $ROOT/build)
#   OUT_DIR       node layer output dir (default $BUILD_DIR/layers/node)
set -euo pipefail

NODE_VERSION="${NODE_VERSION:-24.19.0}"
ARCH="${ARCH:-arm64}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/build}"
OUT_DIR="${OUT_DIR:-$BUILD_DIR/layers/node}"

echo "====> build_node.sh: Node $NODE_VERSION (linux-$ARCH) -> L1 node layer"

TARBALL="$BUILD_DIR/node-v${NODE_VERSION}-linux-${ARCH}.tar.xz"
download_node() {
    if [ ! -f "$TARBALL" ]; then
        echo "====> downloading Node $NODE_VERSION"
        curl -fL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${ARCH}.tar.xz" \
            -o "$TARBALL"
    else
        echo "====> reusing cached Node tarball: $TARBALL"
    fi
}
download_node

# Verify checksum from the official SHASUMS256.txt (best-effort; no network ->
# skip silently, production must have it cached).
if command -v sha256sum >/dev/null 2>&1 && [ -f "${TARBALL}.sha256" ]; then
    echo "====> verifying node checksum"
    (cd "$BUILD_DIR" && sha256sum -c "${TARBALL}.sha256" >/dev/null) || {
        echo "node tarball checksum mismatch" >&2; exit 1; }
fi

# Extract to the layer dir so the top-level entries are bin/, lib/, share/ etc.
# (this is the content proot will overlay onto the guest /usr/local).
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
tar -xJf "$TARBALL" -C "$OUT_DIR" --strip-components=1

echo "====> node layer content:"
ls -la "$OUT_DIR" | head -20
echo "====> node layer prepared at: $OUT_DIR"
echo "====> pack with: tools/pack_runtime.sh node $OUT_DIR $BUILD_DIR/layers [18.x|24.19.0] [arch]"
