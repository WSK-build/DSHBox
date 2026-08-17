#!/usr/bin/env bash
# Placeholder for the test/build matrix helper. The real matrix is driven by
# CI test matrix (Android 10/12/14/15/16, 4/6/8/12GB RAM).
set -euo pipefail
cat <<'TEXT'
Test matrix:
  Android 10 / ARM64
  Android 12 / ARM64
  Android 14 / ARM64
  Android 15 / ARM64
  Android 16 / ARM64
RAM buckets: 4GB, 6GB, 8GB, 12GB+
Real device first, emulator/CI as supplement.
TEXT
