#!/usr/bin/env bash
# Build a Debian ARM64 rootfs for the offline Runtime Bundle.
#
# Modes:
#   debootstrap : build on an x86_64 Linux host with qemu-user-static
#   docker      : build with Docker/Podman and export the container rootfs
#   proot-distro: install from a local/remote rootfs tar on a Termux/PRoot host
#
# Usage:
#   build_rootfs.sh debootstrap [suite] [arch] [mirror]
#   build_rootfs.sh docker [suite] [arch]
#   build_rootfs.sh proot-distro [alias] [rootfs_tar]
set -euo pipefail

MODE="${1:-docker}"
SUITE="${2:-trixie}"
ARCH="${3:-arm64}"
MIRROR="${4:-http://deb.debian.org/debian}"

OUT_DIR="${OUT_DIR:-$PWD/build/rootfs}"
ROOTFS_DIR="${OUT_DIR}/${SUITE}-${ARCH}"
OUTPUT_TAR="${OUTPUT_TAR:-$PWD/build/${SUITE}-${ARCH}-rootfs.tar.gz}"

mkdir -p "$(dirname "$ROOTFS_DIR")"

case "$MODE" in
  debootstrap)
    if ! command -v debootstrap >/dev/null 2>&1; then
      echo "debootstrap not found. Install: sudo apt-get install debootstrap qemu-user-static" >&2
      exit 1
    fi
    echo "==> debootstrap $SUITE $ARCH -> $ROOTFS_DIR"
    if [ "$(uname -m)" = "aarch64" ]; then
      sudo debootstrap --arch="$ARCH" --variant=minbase "$SUITE" "$ROOTFS_DIR" "$MIRROR"
    else
      sudo debootstrap --arch="$ARCH" --variant=minbase --foreign "$SUITE" "$ROOTFS_DIR" "$MIRROR"
      QEMU_STATIC="/usr/bin/qemu-${ARCH}-static"
      if [ -f "$QEMU_STATIC" ]; then
        sudo cp "$QEMU_STATIC" "$ROOTFS_DIR/usr/bin/"
        sudo chroot "$ROOTFS_DIR" /debootstrap/debootstrap --second-stage
        sudo rm -f "$ROOTFS_DIR/usr/bin/qemu-${ARCH}-static"
      else
        echo "qemu-${ARCH}-static not found; install qemu-user-static, then re-run." >&2
        exit 1
      fi
    fi
    echo "==> rootfs ready: $ROOTFS_DIR"
    echo "==> package with: OUT_DIR=$OUT_DIR tools/pack_runtime.sh $ROOTFS_DIR $OUTPUT_TAR"
    ;;
  docker)
    DOCKER_BIN="$(command -v docker || true)"
    if [ -z "$DOCKER_BIN" ]; then
      echo "docker not found in Linux PATH. Use podman or install docker inside Linux." >&2
      exit 1
    fi
    case "$DOCKER_BIN" in
      /mnt/*)
        echo "refusing to use Windows Docker: $DOCKER_BIN" >&2
        echo "per project policy, never touch or invoke Windows-host tools." >&2
        exit 1
        ;;
    esac
    echo "==> docker build runtime-bundle-rootfs"
    docker build -f runtime-bundle/Dockerfile \
      --build-arg SUITE="$SUITE" \
      -t dshapp-rootfs:latest .
    CONTAINER_ID="$(docker create dshapp-rootfs:latest)"
    mkdir -p "$ROOTFS_DIR"
    docker export "$CONTAINER_ID" | tar -x -C "$ROOTFS_DIR"
    docker rm "$CONTAINER_ID" >/dev/null
    echo "==> rootfs ready: $ROOTFS_DIR"
    echo "==> package with: tools/pack_runtime.sh $ROOTFS_DIR $OUTPUT_TAR"
    ;;
  proot-distro)
    ALIAS="${3:-dshapp-debian}"
    ROOTFS_TAR="${4:?usage: build_rootfs.sh proot-distro <alias> <rootfs_tar>}"
    if ! command -v proot-distro >/dev/null 2>&1; then
      echo "proot-distro not found. Install it inside Termux/PRoot first." >&2
      exit 1
    fi
    proot-distro install --override-alias "$ALIAS" --rootfs "$ROOTFS_TAR"
    ;;
  *)
    echo "unknown mode: $MODE" >&2
    exit 1
    ;;
esac
