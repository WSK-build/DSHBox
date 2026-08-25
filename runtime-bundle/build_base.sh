#!/usr/bin/env bash
# build_base.sh - Build the L0 "base" layer (trimmed Debian minbase rootfs).
#
# This replaces the base-rootfs portion of the old monolithic
# build_arm64_runtime_bundle.sh. It produces ONLY the Debian layer:
#   - no Node.js (that is the L1 node layer)
#   - no DSH (that is the standalone DSH product)
#   - no android-side proot (that is the L3 android-side layer)
#
# Build MUST run in WSL2/Linux (debootstrap + qemu-user-static). Windows
# host + Windows Docker are forbidden by project policy (build_rootfs.sh:57).
#
# Usage:
#   runtime-bundle/build_base.sh [suite] [arch] [mirror]
#
# Environment:
#   BUILD_DIR   build work root (default $ROOT/build)
#   OUT_DIR     base layer output dir (default $BUILD_DIR/layers/base)
#   SUITE/ARCH/MIRROR overridable via args
set -euo pipefail

SUITE="${1:-trixie}"
ARCH="${2:-arm64}"
MIRROR="${3:-http://deb.debian.org/debian}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/build}"
OUT_DIR="${OUT_DIR:-$BUILD_DIR/layers/base}"
ROOTFS_DIR="$BUILD_DIR/rootfs/${SUITE}-${ARCH}"

if ! command -v debootstrap >/dev/null 2>&1; then
    echo "missing debootstrap; install: sudo apt-get install debootstrap qemu-user-static" >&2
    exit 1
fi

echo "====> build_base.sh: debootstrap $SUITE $ARCH (L0 base)"
sudo debootstrap --arch="$ARCH" --variant=minbase --foreign "$SUITE" "$ROOTFS_DIR" "$MIRROR"

echo "====> qemu second-stage"
if [ ! -f "/usr/bin/qemu-${ARCH}-static" ]; then
    echo "missing /usr/bin/qemu-${ARCH}-static; install qemu-user-static" >&2
    exit 1
fi
sudo cp "/usr/bin/qemu-${ARCH}-static" "$ROOTFS_DIR/usr/bin/"
sudo mount -t proc proc "$ROOTFS_DIR/proc" 2>/dev/null || true
sudo chroot "$ROOTFS_DIR" /debootstrap/debootstrap --second-stage
sudo umount "$ROOTFS_DIR/proc" 2>/dev/null || true
sudo rm -f "$ROOTFS_DIR/usr/bin/qemu-${ARCH}-static"

echo "====> apt packages (base toolchain; no dev/toolchain L4 packages)"
# WSL resolver only for the build; it must NOT ship (unreachable on Android).
sudo cp /etc/resolv.conf "$ROOTFS_DIR/etc/resolv.conf"
sudo chroot "$ROOTFS_DIR" /usr/bin/env DEBIAN_FRONTEND=noninteractive /usr/bin/apt-get update
sudo chroot "$ROOTFS_DIR" /usr/bin/env DEBIAN_FRONTEND=noninteractive /usr/bin/apt-get install -y --no-install-recommends \
    ca-certificates curl wget openssl git openssh-client rsync \
    coreutils util-linux procps findutils grep sed gawk diffutils \
    tar gzip bzip2 xz-utils zip unzip file less \
    python3 python3-pip python3-venv \
    locales tzdata apt-utils \
    && rm -rf "$ROOTFS_DIR/var/lib/apt/lists/*"

# Reject the WSL resolver everywhere: it is unreachable on Android and would
# break DSH outbound API calls. The app also rewrites it as a safety net.
printf 'nameserver 114.114.114.114\nnameserver 8.8.8.8\nnameserver 223.5.5.5\n' | sudo tee "$ROOTFS_DIR/etc/resolv.conf" >/dev/null

echo "====> trim: locale (keep only C/C.UTF-8/zh_CN.UTF-8) + doc (keep copyright)"
# locale: only keep the three supported locales so LANG=C.UTF-8 still matches
# (Chinese read/render unaffected; only program-UI language may fall back to
# English per the plan). Non-Chinese locales are removed.
find "$ROOTFS_DIR/usr/share/locale" -mindepth 1 -maxdepth 1 -type d \
  ! -name 'C' ! -name 'C.UTF-8' ! -name 'zh_CN' ! -name 'zh_CN.utf8' \
  -exec sudo rm -rf {} + 2>/dev/null || true
# locale.alias maps stored in /usr/share/locale/locale.alias (intentionally kept).

# doc: keep every package's copyright (license declaration), delete only
# changelog/README/real-doc fluff (hard constraint #8).
if [ -d "$ROOTFS_DIR/usr/share/doc" ]; then
    find "$ROOTFS_DIR/usr/share/doc" -type f ! -name 'copyright' -exec sudo rm -f {} + 2>/dev/null || true
    # remove empty per-package doc dirs that only held the deleted files
    find "$ROOTFS_DIR/usr/share/doc" -type d -empty -exec sudo rm -rf {} + 2>/dev/null || true
    sudo rm -rf "$ROOTFS_DIR/usr/share/doc/$(basename "$ROOTFS_DIR" 2>/dev/null)" 2>/dev/null || true
fi
# man pages are large and not needed by the Agent core (hard constraint noise).
sudo rm -rf "$ROOTFS_DIR/usr/share/man" 2>/dev/null || true

echo "====> base layer prepared at: $ROOTFS_DIR"
echo "====> pack with: tools/pack_runtime.sh base $ROOTFS_DIR $OUT_DIR [version] [arch]"
