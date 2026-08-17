#!/usr/bin/env bash
# Verify dist artifacts: SHA256SUMS, APK signatures, APK badging.
set -euo pipefail

DIST_DIR="${DIST_DIR:-$PWD/dist}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/dev/android-sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"

if [ ! -d "$DIST_DIR" ]; then
    echo "dist dir not found: $DIST_DIR" >&2
    exit 1
fi

echo "==> SHA256SUMS verification"
REPO_ROOT="$(cd "$DIST_DIR/.." && pwd)"
cd "$REPO_ROOT"
sha256sum -c dist/SHA256SUMS

echo "==> APK signature/badging"
for apk in "$DIST_DIR"/*.apk; do
    [ -e "$apk" ] || continue
    echo "--- $(basename "$apk")"
    "$BUILD_TOOLS/apksigner" verify --print-certs "$apk" | head -5
    "$BUILD_TOOLS/aapt" dump badging "$apk" | grep -E "^package|^sdkVersion|^targetSdkVersion|^application-label:|^uses-permission" | head -8
done

echo "==> emulator-x86_64 checksums"
if [ -f "$DIST_DIR/emulator-x86_64/SHA256SUMS" ]; then
    cd "$DIST_DIR/emulator-x86_64"
    sha256sum -c SHA256SUMS
fi

echo "dist verification passed"
