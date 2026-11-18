#!/usr/bin/env bash
# build-llama-htp.sh —— 产出 llama.cpp 的 HTP 原生库（host + skel）
#
# 为什么不把它们提交进仓库：
#   libllama.so 约 42 MB、libggml-common 57 MB，合计 100 MB+；而且
#   libggml-htp-v8x.so 是 Qualcomm 的 DSP 二进制，随 GPL 仓库分发有许可问题。
#
# 配方来源：llama.cpp 官方 CI（.github/workflows/build-and-test-snapdragon.yml）
#   container: ghcr.io/snapdragon-toolchain/arm64-android:v0.8   ← 内含 Hexagon SDK
#   cp docs/backend/snapdragon/CMakeUserPresets.json .
#   cmake --preset arm64-android-snapdragon-release -B build
#   cmake --build build
# 不要自己拼 HEXAGON_SDK_ROOT —— SDK 的 hexagon_fun.cmake 会因为变量缺失直接
# FATAL_ERROR（实测踩过）。官方容器里这些变量都已就绪。
#
# 前置：docker（或 podman），能拉 ghcr.io。
# 用法：bash npu-runtime/scripts/build-llama-htp.sh [--out <dir>]
set -euo pipefail

OUT="$1"; [ "${OUT:-}" = "--out" ] && OUT="${2:-}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$ROOT/src/main/jniLibs/arm64-v8a}"
INC="$ROOT/src/main/cpp/include"
LLAMA_REF="${LLAMA_REF:-master}"
IMAGE="ghcr.io/snapdragon-toolchain/arm64-android:v0.8"

command -v docker >/dev/null || { echo "需要 docker"; exit 2; }

echo "== 1/3 在官方容器里编译 llama.cpp（会拉 ~3 GB 镜像）"
docker run --rm -v "$PWD:/io" -w /io "$IMAGE" bash -c '
  set -euo pipefail
  git clone --depth 1 --branch '"$LLAMA_REF"' https://github.com/ggml-org/llama.cpp /llama.cpp
  cd /llama.cpp
  cp docs/backend/snapdragon/CMakeUserPresets.json .
  # 关掉 OpenCL：libggml.so 会 NEEDED libggml-opencl.so，而它又要 libOpenCL.so，
  # 在 App 的 namespace 里解不开（即使声明了 uses-native-library 也不稳）。
  # 我们只用 HTP。
  cmake --preset arm64-android-snapdragon-release -B build -DGGML_OPENCL=OFF
  cmake --build build -j"$(nproc)" --target llama ggml
  mkdir -p /io/out
  cp build/bin/*.so /io/out/ 2>/dev/null || true
  cp build/ggml/src/ggml-hexagon/*.so /io/out/ 2>/dev/null || true
  cp build/ggml/src/ggml-hexagon/ship/libc++_shared.so /io/out/ 2>/dev/null || true
  mkdir -p /io/inc
  cp include/llama.h /io/inc/
  cp ggml/include/*.h /io/inc/
  ls -la /io/out
'

echo "== 2/3 拷贝产物"
mkdir -p "$OUT" "$INC"
# host 侧（按需）
for f in libllama.so libggml.so libggml-base.so libggml-cpu.so libggml-hexagon.so libc++_shared.so; do
  [ -f "out/$f" ] && cp "out/$f" "$OUT/" && echo "  $f"
done
# DSP 侧 skel（按设备 HTP 版本挑一个即可；全带会大 ~6 MB）
for f in out/libggml-htp-v*.so; do
  [ -f "$f" ] && cp "$f" "$OUT/" && echo "  $(basename "$f")"
done
cp inc/*.h "$INC/" && echo "  头文件 → $INC"

echo "== 3/3 完成"
ls -la "$OUT" | head -20
echo
echo "提示：只保留本机 HTP 版本可省 5 MB，例如设备是 v81 就删掉其它 libggml-htp-v*.so"
