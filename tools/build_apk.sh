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
# Layered distribution source dir (dist has base.tar.* / node.tar.* /
# android-side.tar.* + runtime-profile.json). Default from the classic dist name.
LAYERS_DIR="${LAYERS_DIR:-$ROOT_DIR/dist}"
BUNDLE="${1:-$ROOT_DIR/dist/dshapp-runtime-debian-arm64-rootfs-fixed-0.1.0.tar.gz}"
VARIANT="${2:-release}"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets/runtime"

embed_layered() {
    local src="$1"
    echo "==> embedding LAYERED runtime into APK assets (from $src)"
    rm -rf "$ASSETS_DIR"
    mkdir -p "$ASSETS_DIR"
    for layer in base node android-side; do
        local f="$(ls "$src/${layer}.tar."* 2>/dev/null | head -1)"
        [ -n "$f" ] && [ -f "$f" ] || { echo "missing layer $layer in $src" >&2; return 1; }
        cp "$f" "$ASSETS_DIR/$(basename "$f")"
        [ -f "$f.sha256" ] && cp "$f.sha256" "$ASSETS_DIR/$(basename "$f").sha256"
    done
    [ -f "$src/runtime-profile.json" ] && cp "$src/runtime-profile.json" "$ASSETS_DIR/runtime-profile.json"
    ls -la "$ASSETS_DIR"
}

if [ -f "$LAYERS_DIR/runtime-profile.json" ] || ls "$LAYERS_DIR"/base.tar.* >/dev/null 2>&1; then
    embed_layered "$LAYERS_DIR"
else
    [ -f "$BUNDLE" ] || { echo "bundle not found: $BUNDLE" >&2; exit 1; }
    # User-data leak check: the bundle must NOT contain DSH user data (sessions,
    # credentials/API keys, profile installs). A snapshot taken from a running
    # device without exclusions carries the previous owner's private data into the
    # APK - refuse loudly instead of shipping it.
    LEAK_COUNT=$(tar -tzf "$BUNDLE" | grep -cE "root/\.dsh|credentials\.yaml" || true)
    [ "$LEAK_COUNT" -eq 0 ] || { echo "REFUSING: bundle contains DSH user data (root/.dsh); rebuild with user data excluded" >&2; exit 1; }
    echo "==> embedding LEGACY single runtime bundle into APK assets"
    rm -rf "$ASSETS_DIR"
    mkdir -p "$ASSETS_DIR"
    cp "$BUNDLE" "$ASSETS_DIR/dshapp-runtime.dshb"
    sha256sum "$BUNDLE" | awk '{print $1}' > "$ASSETS_DIR/dshapp-runtime.dshb.sha256"
    ls -la "$ASSETS_DIR"
fi

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
