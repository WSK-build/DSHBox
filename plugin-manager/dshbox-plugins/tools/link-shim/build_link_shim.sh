#!/usr/bin/env bash
# =============================================================================
# 构建 apt/dpkg 用的硬链接垫片（libdshbox-link.so，aarch64/glibc）
#
# 目标环境是 guest 的 Debian trixie（glibc / aarch64），**不能**用 Android NDK（bionic）。
# 方法沿用《WORKFLOW_DSH_BUILD.md》的思路——在 WSL2（x86_64 宿主）里用**项目自己的
# arm64 rootfs** 加 qemu-aarch64-static；但编译器与包管理器需要按绝对路径 exec 子进程
# （cc1 / as / ld），仅靠 QEMU_LD_PREFIX 不足以完成路径映射，因此这里多一步：
# 用 user namespace 取得**假 root** 后 chroot 进 rootfs。
#
#   · 免 Docker、**免宿主 root**（unshare -r 把当前用户映射为 namespace 内的 root）；
#   · guest 的绝对路径全部落在 rootfs 内，**宿主文件系统不受影响**；
#   · arm64 二进制经 binfmt + qemu-aarch64-static 运行。
#
# 用法（在 WSL2 的 Ubuntu-22.04 内执行）：
#   bash build_link_shim.sh <项目根目录> [输出目录]
# 例：
#   bash build_link_shim.sh /mnt/d/PROJECT/DSHBox1.1.0/DSHBox1.4.0
#
# 前置：~/dshbuild/arm64root 已就位（见 WORKFLOW_DSH_BUILD.md 第一节）。
# 产物：<输出目录>/libdshbox-link.so，并复制到
#       <项目>/source/plugin-manager/dshbox-plugins/assets/dshbox/libdshbox-link.so（随 APK 资产交付）。
# =============================================================================
set -euo pipefail

PROJECT="${1:?用法: build_link_shim.sh <项目根目录> [输出目录]}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/libdshbox-link.c"
INNER="$HERE/build_inner.sh"
OUT_DIR="${2:-$HERE}"

ROOT="${DSHBOX_ARM64_ROOT:-$HOME/dshbuild/arm64root}"
QEMU="${DSHBOX_QEMU:-/usr/bin/qemu-aarch64-static}"

[ -d "$ROOT" ] || { echo "缺少 arm64 rootfs: $ROOT（见 WORKFLOW_DSH_BUILD.md 第一节）"; exit 1; }
[ -f "$SRC" ] || { echo "缺少源码: $SRC"; exit 1; }
[ -x "$QEMU" ] || { echo "缺少 qemu-aarch64-static: $QEMU"; exit 1; }
ls /proc/sys/fs/binfmt_misc/ | grep -q aarch64 || { echo "binfmt 未注册 aarch64"; exit 1; }

mkdir -p "$OUT_DIR"

echo "== [1/2] 修正 rootfs 的 apt 源（出厂层里是已失效镜像）=="
sed -i 's|mirrors\.tuna\.tsinghua\.edu\.cn|deb.debian.org|; s|mirrors\.bfsu\.edu\.cn|deb.debian.org|' \
  "$ROOT/etc/apt/sources.list"
grep -v '^#' "$ROOT/etc/apt/sources.list" | head -1 | sed 's/^/   /'

echo "== [2/2] 进 rootfs 编译（user namespace 假 root + chroot）=="
# 说明：WSL 内无法把宿主 /proc bind 进 rootfs，且 guest 下载与编译都不依赖它，
# 因此只补几个设备节点（假 root 在 namespace 内可 mknod），源码用复制而不是 bind——
# 挂载只存在于该 namespace 内，退出即消失。
mkdir -p "$ROOT/out"
cp "$SRC" "$INNER" "$ROOT/out/"
unshare -r -m bash -c "
  set -e
  for dev in 'null c 1 3' 'zero c 1 5' 'random c 1 8' 'urandom c 1 9'; do
    set -- \$dev
    if [ ! -c '$ROOT/dev/'\$1 ]; then
      rm -f '$ROOT/dev/'\$1
      mknod -m 666 '$ROOT/dev/'\$1 \$2 \$3 \$4 2>/dev/null || true
    fi
  done
  mount -t proc proc '$ROOT/proc' 2>/dev/null || true
  chroot '$ROOT' /usr/bin/bash /out/build_inner.sh
"

if [ -f "$ROOT/out/libdshbox-link.so" ]; then
  cp "$ROOT/out/libdshbox-link.so" "$OUT_DIR/libdshbox-link.so"
fi
echo "== 产物校验 =="
file "$OUT_DIR/libdshbox-link.so" || true
readelf -h "$OUT_DIR/libdshbox-link.so" 2>/dev/null | grep -E "Class|Machine" || true

ASSET="$PROJECT/source/plugin-manager/dshbox-plugins/assets/dshbox"
if [ -d "$ASSET" ] && [ "$OUT_DIR" != "$ASSET" ]; then
  cp "$OUT_DIR/libdshbox-link.so" "$ASSET/libdshbox-link.so"
  echo "已复制为 APK 资产: $ASSET/libdshbox-link.so"
fi
echo "完成：$OUT_DIR/libdshbox-link.so"
