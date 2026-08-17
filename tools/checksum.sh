#!/usr/bin/env bash
# Generate SHA-256 checksums for release bundles.
set -euo pipefail
for f in "$@"; do
    if [ -f "$f" ]; then
        sha256sum "$f"
    fi
done
