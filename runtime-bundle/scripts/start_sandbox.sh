#!/system/bin/sh
# start_sandbox.sh - PRoot entry point for the packaged Android-side runtime.
#
# Expects runtime-current/android-side/{bin,lib,libexec/proot/loader} and
# runtime-current/debian rootfs. All paths are app-specific storage paths.

set -eu

APP_FILES_DIR="${1:?usage: start_sandbox.sh <app_files_dir>}"
RUNTIME_DIR="${2:-$APP_FILES_DIR/runtime/runtime-current}"
ROOTFS_DIR="$RUNTIME_DIR/debian"
PROOT_BIN="${PROOT_BIN:-$RUNTIME_DIR/android-side/bin/proot}"
PROOT_LIB="${PROOT_LIB:-$RUNTIME_DIR/android-side/lib}"
PROOT_LOADER="${PROOT_LOADER:-$RUNTIME_DIR/android-side/libexec/proot/loader}"
PROOT_TMP_DIR="${PROOT_TMP_DIR:-$RUNTIME_DIR/tmp}"

if [ ! -x "$PROOT_BIN" ]; then
    echo "proot binary not found: $PROOT_BIN" >&2
    exit 1
fi

mkdir -p "$PROOT_TMP_DIR"
export LD_LIBRARY_PATH="$PROOT_LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PROOT_LOADER="$PROOT_LOADER"
export PROOT_TMP_DIR="$PROOT_TMP_DIR"

# PRoot flags keep /proc, /dev, /dev/shm, /tmp, sockets, symlinks and
# permissions working. The Debian rootfs is mounted as the Linux root.
exec "$PROOT_BIN" \
    --rootfs="$ROOTFS_DIR" \
    --bind=/system \
    --bind=/apex \
    --bind=/proc \
    --bind=/dev \
    --bind="$APP_FILES_DIR/user-data:/root/projects" \
    --cwd=/root \
    /system/bin/sh -c 'exec /usr/bin/bash /opt/dshapp/start_dsh.sh' 
