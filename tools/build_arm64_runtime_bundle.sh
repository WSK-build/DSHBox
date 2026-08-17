#!/usr/bin/env bash
# Build the ARM64 Debian Runtime Bundle from scratch in Linux.
#
# This script codifies the manual process validated during Phase 0/1:
#   debootstrap trixie arm64 -> qemu second stage -> apt packages ->
#   Node.js binary -> DSH via host npm + ARM64 native packages ->
#   start scripts -> tar.gz + SHA256.
#
# Prerequisites:
#   sudo apt-get install -y debootstrap qemu-user-static
#   host Node.js 22+ and npm in PATH
#   tools/pack_runtime.sh present
#
# Usage:
#   tools/build_arm64_runtime_bundle.sh [suite] [arch]
set -euo pipefail

SUITE="${1:-trixie}"
ARCH="${2:-arm64}"
NODE_VERSION="${NODE_VERSION:-22.17.0}"
DSH_VERSION="${DSH_VERSION:-0.1.0-rc.6}"
MIRROR="${MIRROR:-http://deb.debian.org/debian}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/build/rootfs}"
ROOTFS_DIR="$BUILD_DIR/${SUITE}-${ARCH}"
BUNDLE_OUT="${BUNDLE_OUT:-$ROOT_DIR/build/dshapp-runtime-debian-${ARCH}-rootfs.tar.gz}"
TMP_NPM="${TMP_NPM:-/tmp/dshapp-dsh-x64-runtime}"
TMP_NATIVE="${TMP_NATIVE:-/tmp/dshapp-arm64-native}"

if ! command -v debootstrap >/dev/null 2>&1; then
    echo "missing debootstrap; install: sudo apt-get install debootstrap qemu-user-static" >&2
    exit 1
fi
if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    echo "missing host node/npm" >&2
    exit 1
fi

echo "==> Stage 1: debootstrap $SUITE $ARCH"
sudo debootstrap --arch="$ARCH" --variant=minbase --foreign "$SUITE" "$ROOTFS_DIR" "$MIRROR"

echo "==> Stage 2: qemu second-stage"
if [ ! -f "/usr/bin/qemu-${ARCH}-static" ]; then
    echo "missing /usr/bin/qemu-${ARCH}-static; install qemu-user-static" >&2
    exit 1
fi
sudo cp "/usr/bin/qemu-${ARCH}-static" "$ROOTFS_DIR/usr/bin/"
sudo mount -t proc proc "$ROOTFS_DIR/proc" 2>/dev/null || true
sudo chroot "$ROOTFS_DIR" /debootstrap/debootstrap --second-stage
sudo umount "$ROOTFS_DIR/proc" 2>/dev/null || true
sudo rm -f "$ROOTFS_DIR/usr/bin/qemu-${ARCH}-static"

echo "==> Stage 3: apt packages"
# apt inside the WSL build needs the WSL resolver, but that file must NOT
# ship in the bundle: it is unreachable on Android and breaks DSH's outbound
# API calls. Use the WSL DNS for the build, then reset before packing (the
# app also rewrites it at runtime as a safety net).
sudo cp /etc/resolv.conf "$ROOTFS_DIR/etc/resolv.conf"
sudo chroot "$ROOTFS_DIR" /usr/bin/env DEBIAN_FRONTEND=noninteractive /usr/bin/apt-get update
sudo chroot "$ROOTFS_DIR" /usr/bin/env DEBIAN_FRONTEND=noninteractive /usr/bin/apt-get install -y --no-install-recommends \
    ca-certificates curl wget openssl git openssh-client rsync make gcc g++ cmake pkg-config \
    python3 python3-pip python3-venv procps findutils grep sed gawk diffutils tar gzip bzip2 xz-utils \
    zip unzip file less locales tzdata

echo "==> Stage 4: Node.js $NODE_VERSION"
curl -fL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${ARCH}.tar.xz" \
    -o "$BUILD_DIR/node.tar.xz"
sudo tar -xJf "$BUILD_DIR/node.tar.xz" -C "$ROOTFS_DIR/usr/local" --strip-components=1

echo "==> Stage 5: DSH $DSH_VERSION"
rm -rf "$TMP_NPM" "$TMP_NATIVE"
mkdir -p "$TMP_NPM" "$TMP_NATIVE"
npm install --prefix "$TMP_NPM/runtime" "npm:@deepseek-ai/dsh@${DSH_VERSION}" \
    --registry=https://registry.npmjs.org --no-audit --no-fund
sudo mkdir -p "$ROOTFS_DIR/opt/dshapp/runtime"
sudo tar -C "$TMP_NPM/runtime" -cf - . | sudo tar -C "$ROOTFS_DIR/opt/dshapp/runtime" -xf -

# Replace x64 native packages with ARM64 equivalents.
(cd "$TMP_NATIVE" && npm pack @img/sharp-linux-arm64@0.35.3 @img/sharp-libvips-linux-arm64@1.3.2 \
    @koromix/koffi-linux-arm64@3.1.5 node-addon-require-builtin-linux-arm64-gnu@0.1.5 >/dev/null)
# @vscode/ripgrep's platform optional dep was installed as -linux-x64 (npm ran
# on the x64 build host); the DSH grep/glob tools spawn that binary, which
# cannot exec on arm64. Version must match the installed @vscode/ripgrep.
RG_VERSION="$(node -p "require('$ROOTFS_DIR/opt/dshapp/runtime/node_modules/@vscode/ripgrep/package.json').version")"
(cd "$TMP_NATIVE" && npm pack "@vscode/ripgrep-linux-arm64@${RG_VERSION}" >/dev/null)
for tgz in "$TMP_NATIVE"/*.tgz; do
    case "$(basename "$tgz")" in
        img-sharp-linux-arm64-*.tgz) dest="$ROOTFS_DIR/opt/dshapp/runtime/node_modules/@img/sharp-linux-arm64" ;;
        img-sharp-libvips-linux-arm64-*.tgz) dest="$ROOTFS_DIR/opt/dshapp/runtime/node_modules/@img/sharp-libvips-linux-arm64" ;;
        koromix-koffi-linux-arm64-*.tgz) dest="$ROOTFS_DIR/opt/dshapp/runtime/node_modules/@koromix/koffi-linux-arm64" ;;
        node-addon-require-builtin-linux-arm64-gnu-*.tgz) dest="$ROOTFS_DIR/opt/dshapp/runtime/node_modules/node-addon-require-builtin-linux-arm64-gnu" ;;
        vscode-ripgrep-linux-arm64-*.tgz) dest="$ROOTFS_DIR/opt/dshapp/runtime/node_modules/@vscode/ripgrep-linux-arm64" ;;
        *) continue ;;
    esac
    rm -rf "$TMP_NATIVE/pkg"; mkdir "$TMP_NATIVE/pkg"
    tar -xzf "$tgz" -C "$TMP_NATIVE/pkg"
    sudo rm -rf "$dest"; sudo mkdir -p "$dest"
    sudo cp -r "$TMP_NATIVE/pkg/package/." "$dest/"
    rm -rf "$TMP_NATIVE/pkg"
done
sudo rm -rf \
    "$ROOTFS_DIR/opt/dshapp/runtime/node_modules/@img/sharp-linux-x64" \
    "$ROOTFS_DIR/opt/dshapp/runtime/node_modules/@img/sharp-libvips-linux-x64" \
    "$ROOTFS_DIR/opt/dshapp/runtime/node_modules/@koromix/koffi-linux-x64" \
    "$ROOTFS_DIR/opt/dshapp/runtime/node_modules/node-addon-require-builtin-linux-x64-gnu" \
    "$ROOTFS_DIR/opt/dshapp/runtime/node_modules/@vscode/ripgrep-linux-x64"

echo "==> Stage 5b: compile node-pty for $ARCH under qemu"
sudo cp "/usr/bin/qemu-${ARCH}-static" "$ROOTFS_DIR/usr/bin/"
sudo mount -t proc proc "$ROOTFS_DIR/proc" 2>/dev/null || true
sudo rm -rf "$ROOTFS_DIR/opt/ptybuild"
sudo chroot "$ROOTFS_DIR" /usr/local/bin/npm install --prefix /opt/ptybuild node-pty@1.1.0 \
    --registry=https://registry.npmjs.org --no-audit --no-fund
sudo rm -rf "$ROOTFS_DIR/opt/dshapp/runtime/node_modules/node-pty"
sudo cp -r "$ROOTFS_DIR/opt/ptybuild/node_modules/node-pty" \
    "$ROOTFS_DIR/opt/dshapp/runtime/node_modules/node-pty"
sudo rm -rf "$ROOTFS_DIR/opt/ptybuild"
sudo umount "$ROOTFS_DIR/proc" 2>/dev/null || true
sudo rm -f "$ROOTFS_DIR/usr/bin/qemu-${ARCH}-static"

echo "==> Stage 6: start scripts + Android DSH patch"
sudo cp "$ROOT_DIR/runtime-bundle/scripts/start_dsh.sh" "$ROOTFS_DIR/opt/dshapp/start_dsh.sh"
sudo cp "$ROOT_DIR/runtime-bundle/scripts/healthcheck.sh" "$ROOTFS_DIR/opt/dshapp/healthcheck.sh"
sudo cp "$ROOT_DIR/runtime-bundle/scripts/patch_dsh_android.js" "$ROOTFS_DIR/opt/dshapp/patch_dsh_android.js"
sudo chmod +x "$ROOTFS_DIR/opt/dshapp/start_dsh.sh" "$ROOTFS_DIR/opt/dshapp/healthcheck.sh"
sudo chroot "$ROOTFS_DIR" /usr/local/bin/node /opt/dshapp/patch_dsh_android.js

# Never ship the WSL resolver (unreachable on Android).
printf 'nameserver 114.114.114.114\nnameserver 8.8.8.8\nnameserver 223.5.5.5\n' | sudo tee "$ROOTFS_DIR/etc/resolv.conf" >/dev/null

echo "==> Stage 7: pack"
"$ROOT_DIR/tools/pack_runtime.sh" "$ROOTFS_DIR" "$BUNDLE_OUT" "0.1.0" "$ARCH"
