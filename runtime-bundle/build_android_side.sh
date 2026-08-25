#!/usr/bin/env bash
# build_android_side.sh - Build the L3 "android-side" layer (proot + shmem + loader).
#
# Android-side holds the static proot binaries that run ON the Android host to
# provide the guest Linux view. They are host-side binaries, NOT part of the
# guest rootfs. The app locates them either from the APK's bundled native libs
# (nativeLibraryDir: libproot.so / libproot-loader.so / libandroid-shmem.so) or
# from runtime-current/android-side as a fallback (see DefaultSandboxManager
# prootBinary()/prootLibDir()/prootLoaderFile()).
#
# This script assembles the android-side layer from prebuilt arm64 binaries. It
# accepts either:
#   1) a directory containing libproot.so / libproot-loader.so /
#      libandroid-shmem.so (and optional libtalloc.so), or
#   2) a prebuilt android-side tarball (dshapp-android-proot-runtime.tar.gz).
#
# Usage:
#   runtime-bundle/build_android_side.sh [src_dir_or_tarball]
#
# Environment:
#   ANDROID_SIDE_SRC  source (dir or tarball); default: app/src/main/jniLibs/arm64-v8a
#   OUT_DIR           android-side layer output dir (default $BUILD_DIR/layers/android-side)
#   BUILD_DIR         build work root (default $ROOT/build)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/build}"
OUT_DIR="${OUT_DIR:-$BUILD_DIR/layers/android-side}"
ANDROID_SIDE_SRC="${ANDROID_SIDE_SRC:-$ROOT_DIR/app/src/main/jniLibs/arm64-v8a}"

if [ -z "$ANDROID_SIDE_SRC" ] || { [ ! -d "$ANDROID_SIDE_SRC" ] && [ ! -f "$ANDROID_SIDE_SRC" ]; }; then
    echo "android-side source not found: $ANDROID_SIDE_SRC" >&2
    echo "set ANDROID_SIDE_SRC to the arm64 proot/shared-lib source dir or tarball" >&2
    exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/bin" "$OUT_DIR/lib" "$OUT_DIR/libexec/proot"

# Stage raw sources into a temp place.
STAGE="$(mktemp -d)"
cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT
if [ -f "$ANDROID_SIDE_SRC" ]; then
    tar -xzf "$ANDROID_SIDE_SRC" -C "$STAGE"
else
    cp -a "$ANDROID_SIDE_SRC"/. "$STAGE/"
fi

# Map the prebuilt names into the android-side layout.
#   libproot.so            -> bin/proot
#   libproot-loader.so     -> libexec/proot/loader
#   libandroid-shmem.so    -> lib/libandroid-shmem.so
#   libtalloc.so (optional)-> lib/libtalloc.so
find_bin() { find "$STAGE" -name "$1" ! -name "*.so.dbg" ! -name "*.sym" 2>/dev/null | head -1; }

PROOT_BIN="$(find_bin libproot.so)";          PROOT_BIN="${PROOT_BIN:-$(find_bin proot)}"
LOADER="$(find_bin libproot-loader.so)";      LOADER="${LOADER:-$(find_bin loader)}"
SHMEM="$(find_bin libandroid-shmem.so)";      SHMEM="${SHMEM:-$(find_bin libandroid-shmem)}"
TALLOC="$(find_bin libtalloc.so)"

[ -n "$PROOT_BIN" ] || { echo "missing libproot.so in $ANDROID_SIDE_SRC" >&2; exit 1; }
[ -n "$LOADER" ]  || { echo "missing libproot-loader.so in $ANDROID_SIDE_SRC" >&2; exit 1; }
[ -n "$SHMEM" ]   || { echo "missing libandroid-shmem.so in $ANDROID_SIDE_SRC" >&2; exit 1; }

cp "$PROOT_BIN" "$OUT_DIR/bin/proot"
cp "$LOADER" "$OUT_DIR/libexec/proot/loader"
cp "$SHMEM" "$OUT_DIR/lib/libandroid-shmem.so"
[ -n "$TALLOC" ] && cp "$TALLOC" "$OUT_DIR/lib/libtalloc.so"
chmod +x "$OUT_DIR/bin/proot" "$OUT_DIR/libexec/proot/loader"

echo "====> android-side layer:"
find "$OUT_DIR" -type f -printf '%P\n' | sort
echo "====> pack with: tools/pack_runtime.sh android-side $OUT_DIR $BUILD_DIR/layers [version] [arch]"
