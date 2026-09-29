#!/usr/bin/env bash
# 在 chroot 进 arm64 rootfs 之后执行：取编译器 → 编译垫片。
# 由 build_link_shim.sh 调用；源码与产物目录已 bind 到 /out。
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

if [ ! -x /usr/bin/gcc ]; then
  echo "== 取编译器（apt 只下载；解包用 --no-same-owner，故不受属主限制影响）=="
  # APT::Sandbox::User=root：userns 内已是假 root，跳过切换 _apt 用户（否则 setuid 失败）。
  apt-get -o APT::Sandbox::User=root update
  apt-get -o APT::Sandbox::User=root install --download-only -y --no-install-recommends gcc libc6-dev
  for d in /var/cache/apt/archives/*.deb; do
    [ -f "$d" ] || continue
    dpkg-deb --fsys-tarfile "$d" | tar -x --no-same-owner -p -C /
  done
fi

echo "== 编译器 =="
/usr/bin/gcc --version | head -1
echo "== rootfs 架构 =="
/usr/bin/dpkg --print-architecture

echo "== 编译 =="
# -s：去掉符号表（只保留动态符号），asset 体积更小。
/usr/bin/gcc -shared -fPIC -O2 -Wall -Wextra -s -o /out/libdshbox-link.so /out/libdshbox-link.c -ldl
ls -l /out/libdshbox-link.so
