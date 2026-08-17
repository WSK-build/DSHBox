#!/usr/bin/env bash
# Pack a Debian ARM64 rootfs into an offline Runtime Bundle tar.gz.
# Usage: pack_runtime.sh <rootfs_dir> <output_file.tar.gz> [version] [arch]
set -euo pipefail

ROOTFS_DIR="${1:?usage: pack_runtime.sh <rootfs_dir> <output_file.tar.gz> [version] [arch]}"
OUTPUT_FILE="${2:?usage: pack_runtime.sh <rootfs_dir> <output_file.tar.gz> [version] [arch]}"
VERSION="${3:-0.1.0}"
ARCH="${4:-arm64}"

if [ ! -d "$ROOTFS_DIR" ]; then
    echo "rootfs dir not found: $ROOTFS_DIR" >&2
    exit 1
fi

OUTPUT_DIR="$(dirname "$OUTPUT_FILE")"
mkdir -p "$OUTPUT_DIR"

echo "packing $ROOTFS_DIR -> $OUTPUT_FILE"
tar -C "$ROOTFS_DIR" -czf "$OUTPUT_FILE" .

SHA256="$(sha256sum "$OUTPUT_FILE" | awk '{print $1}')"
SIZE_BYTES="$(stat -c%s "$OUTPUT_FILE")"

cat <<YAML
# bundle manifest (append to runtime-bundle/bundle.yaml)
bundle:
  kind: runtime
  name: dshapp-runtime-debian-${ARCH}
  version: ${VERSION}
  arch: ${ARCH}
  built_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)
  source: local-build
  sha256: ${SHA256}
  size_bytes: ${SIZE_BYTES}
YAML

echo "${SHA256}  ${OUTPUT_FILE}" > "${OUTPUT_FILE}.sha256"
echo "checksum written to ${OUTPUT_FILE}.sha256"
