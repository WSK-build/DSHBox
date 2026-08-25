#!/usr/bin/env bash
# pack_runtime.sh - Pack ONE runtime layer into `<name>.tar.zst` (+ .sha256).
#
# New layered distribution: a bundle is the set of layer tarballs plus a
# runtime-profile.json (see gen_profile.sh) and a bundle.yaml. This packs a
# single layer so base/node/android-side each get an independently checksummed
# artifact that the app unpacks and verifies per-layer.
#
# Usage:
#   pack_runtime.sh <layer_name> <layer_dir> <out_dir> [version] [arch] [zstd_level]
#
#   layer_name   base | node | android-side
#   layer_dir    directory to pack (its CONTENT is the layer; top-level entries
#                are preserved, e.g. bin/, lib/, usr/ for base)
#   out_dir      where <layer_name>.tar.zst (.sha256) land
#   version      layer version (default 0.1.0)
#   arch         arch (default arm64)
#   zstd_level   zstd compression level (default 19)
#
# Output: <out_dir>/<layer_name>.tar.zst + <out_dir>/<layer_name>.tar.zst.sha256
#   and prints a YAML layer manifest fragment for bundle.yaml.
#
# If the `zstd` CLI is unavailable, degrade to gzip (recorded clearly) so a build
# never silently stalls; the caller inspects the file extension to know.
set -euo pipefail

LAYER_NAME="${1:?usage: pack_runtime.sh <layer_name> <layer_dir> <out_dir> [version] [arch] [zstd_level]}"
LAYER_DIR="${2:?usage: pack_runtime.sh <layer_name> <layer_dir> <out_dir> [version] [arch] [zstd_level]}"
OUT_DIR="${3:?usage: pack_runtime.sh <layer_name> <layer_dir> <out_dir> [version] [arch] [zstd_level]}"
VERSION="${4:-0.1.0}"
ARCH="${5:-arm64}"
ZSTD_LEVEL="${6:-19}"

if [ ! -d "$LAYER_DIR" ]; then
    echo "layer dir not found: $LAYER_DIR" >&2
    exit 1
fi
mkdir -p "$OUT_DIR"

ZSTD_BIN="$(command -v zstd || true)"
# Default to gzip for the Android-consumed artifact: the app's zstd decompression
# needs com.github.luben:zstd-jni, which is NOT resolvable in the current offline
# build (no network / not cached). Per plan risk table, degrade to gzip and
# record the reason. zstd is retained (opt-in via PACK_COMPRESSION=zstd) for when
# zstd-jni is provisioned (Phase D).
COMPRESSION="${PACK_COMPRESSION:-gzip}"
EXT="tar.gz"
if [ "$COMPRESSION" = "zstd" ]; then
    if [ -z "$ZSTD_BIN" ]; then
        echo "WARNING: PACK_COMPRESSION=zstd but zstd CLI missing; degrading to gzip for $LAYER_NAME" >&2
        COMPRESSION="gzip"
        EXT="tar.gz"
    else
        EXT="tar.zst"
    fi
fi

OUT_FILE="$OUT_DIR/${LAYER_NAME}.${EXT}"

echo "packing layer $LAYER_NAME ($LAYER_DIR) -> $OUT_FILE (compression=$COMPRESSION)"
if [ "$COMPRESSION" = "zstd" ]; then
    # -T0 = auto threads, -$(ZSTD_LEVEL) = level 19 (high ratio).
    tar -C "$LAYER_DIR" -I "$ZSTD_BIN -T0 -$ZSTD_LEVEL" -cf "$OUT_FILE" .
else
    tar -C "$LAYER_DIR" -czf "$OUT_FILE" .
fi

SHA256="$(sha256sum "$OUT_FILE" | awk '{print $1}')"
SIZE_BYTES="$(stat -c%s "$OUT_FILE")"

echo "${SHA256}  ${OUT_FILE}" > "${OUT_FILE}.sha256"

cat <<YAML
  - name: ${LAYER_NAME}
    version: ${VERSION}
    compression: ${COMPRESSION}
    sha256: ${SHA256}
    size_bytes: ${SIZE_BYTES}
    file: $(basename "${OUT_FILE}")
YAML

echo "checksum written to ${OUT_FILE}.sha256"
