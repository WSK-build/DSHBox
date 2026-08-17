#!/usr/bin/env bash
# build_apk.sh - build the install-and-go APK: embeds the fixed runtime bundle
# into the APK assets so a fresh device needs nothing but the APK.
#
# Usage:
#   tools/build_apk.sh [bundle.tar.gz] [debug|release]
#
#   bundle   default: dist/dshapp-runtime-debian-arm64-rootfs-fixed-0.1.0.tar.gz
#   variant  default: release (signed with keystore.properties when present)
#
# Output:
#   app/build/outputs/apk/<variant>/app-<variant>.apk
#
# The embedded bundle must contain the DSHapp fixes; a "<name>.sha256" sidecar
# is generated next to it and verified by the app before first-boot extraction
# (BundledRuntimeInstaller). The bundle itself is NOT tracked by git.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE="${1:-$ROOT_DIR/dist/dshapp-runtime-debian-arm64-rootfs-fixed-0.1.0.tar.gz}"
VARIANT="${2:-release}"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets/runtime"

[ -f "$BUNDLE" ] || { echo "bundle not found: $BUNDLE" >&2; exit 1; }

# User-data leak check: the bundle must NOT contain DSH user data (sessions,
# credentials/API keys, profile installs). A snapshot taken from a running
# device without exclusions carries the previous owner's private data into the
# APK - refuse loudly instead of shipping it.
LEAK_COUNT=$(tar -tzf "$BUNDLE" | grep -cE "root/\.dsh|credentials\.yaml" || true)
[ "$LEAK_COUNT" -eq 0 ] || { echo "REFUSING: bundle contains DSH user data (root/.dsh); rebuild with user data excluded" >&2; exit 1; }

echo "==> embedding runtime bundle into APK assets"
# clean stale assets first so no leftover files (e.g. old bundle hashes) ship
rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR"
cp "$BUNDLE" "$ASSETS_DIR/dshapp-runtime.dshb"
sha256sum "$BUNDLE" | awk '{print $1}' > "$ASSETS_DIR/dshapp-runtime.dshb.sha256"
ls -la "$ASSETS_DIR"

echo "==> building $VARIANT APK"
# in-process Kotlin compilation: avoids a kotlin-daemon that may not be able
# to write its state dir (e.g. sandboxed CI/build environments). A larger heap
# plus capped workers keep the 400MB embedded-bundle compression from killing
# the daemon on small machines.
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-home}" \
    "$ROOT_DIR/gradlew" -p "$ROOT_DIR" -Pkotlin.compiler.execution.strategy=in-process \
    -Dorg.gradle.jvmargs="-Xmx5g -XX:MaxMetaspaceSize=1g" \
    -Dorg.gradle.workers.max=4 \
    ":app:assemble${VARIANT^}"

APK="$ROOT_DIR/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[ -f "$APK" ] || { echo "build produced no APK at $APK" >&2; exit 1; }

echo "==> verifying embedded bundle inside APK"
unzip -l "$APK" | grep -E "assets/runtime/dshapp-runtime" || { echo "bundle missing from APK" >&2; exit 1; }

echo "==> BUILD-OK: $APK ($(du -h "$APK" | awk '{print $1}'))"
echo "    sha256: $(sha256sum "$APK" | awk '{print $1}')"
