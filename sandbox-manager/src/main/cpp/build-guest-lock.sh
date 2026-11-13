#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"
ROOT="${DSHBOX_ARM64_ROOT:?Set DSHBOX_ARM64_ROOT to the ARM64 Debian build rootfs}"
unshare -r -m bash -c '
  set -e
  mount --bind "$1" "$2/mnt"
  chroot "$2" /usr/bin/gcc -O2 -s -Wall -Wextra -Werror \
    /mnt/sandbox-manager/src/main/cpp/dsh_lock.c \
    -o /mnt/plugin-manager/dshbox-plugins/assets/dshbox/lock-helper
' -- "$REPO" "$ROOT"
