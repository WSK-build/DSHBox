#!/system/bin/sh
# init_sandbox.sh - Android side runtime initialization.
# This script is a TEMPLATE: it is executed from Android with app-specific
# storage paths passed as arguments. It must never touch user-data in a
# destructive way.

set -eu

APP_FILES_DIR="${1:?usage: init_sandbox.sh <app_files_dir>}"
BUNDLE_FILE="${2:?usage: init_sandbox.sh <app_files_dir> <bundle_file>}"
RUNTIME_SLOT="${3:-runtime-new}"
echo "bundle file: $BUNDLE_FILE"

RUNTIME_DIR="$APP_FILES_DIR/runtime/$RUNTIME_SLOT"
USER_DATA_DIR="$APP_FILES_DIR/user-data"

if [ ! -d "$APP_FILES_DIR/runtime" ]; then
    mkdir -p "$APP_FILES_DIR/runtime"
fi
if [ ! -d "$USER_DATA_DIR" ]; then
    mkdir -p "$USER_DATA_DIR"
fi

# Do NOT overwrite a working slot unless it is an explicit new-slot install.
if [ -d "$RUNTIME_DIR" ]; then
    echo "runtime slot already exists: $RUNTIME_DIR" >&2
    echo "remove it explicitly only if it is an incomplete new-slot install" >&2
    exit 1
fi

mkdir -p "$RUNTIME_DIR"

# Verify SHA-256 (the actual check is done in Kotlin/BundleManager; this is a shell fallback).
if command -v sha256sum >/dev/null 2>&1; then
    echo "sha256sum fallback should compare against bundle.yaml before extracting" >&2
fi

echo "initialized runtime slot at $RUNTIME_DIR"
