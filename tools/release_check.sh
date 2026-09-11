#!/usr/bin/env bash
# release_check.sh — 发布前门禁（本地运行，也可在 CI 复用）。
#
# 一次跑完发布前必须全绿的四项，任一失败即中止：
#   1. i18n 一致性（六语键位/占位符/复数/硬编码）
#   2. 个人数据泄露审查（本机路径、用户名、凭据文件、设备号样式）
#   3. 全量 JVM 单测
#   4. release 构建（含 lintVital）
#
# 为什么把审计放进门禁：v1.3.1 实测踩到 —— 一份构建文档里带着开发机的
# JDK 绝对路径，并已随仓库公开。这类内容不会被编译器、单测或 lint 发现，
# 只有专门的扫描能拦住。
#
# 用法：
#   bash tools/release_check.sh                 # 全量
#   bash tools/release_check.sh --skip-build    # 跳过构建（快速自检）
#
# 环境：需要 JAVA_HOME 指向 JDK 17+（Android Gradle Plugin 要求；
#      注意 JetBrains 系 jbr 是精简运行时、缺 jlink，不能用于 Android 构建）。

set -u

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

SKIP_BUILD=0
[ "${1:-}" = "--skip-build" ] && SKIP_BUILD=1

# python 可执行名在各平台不同（Windows 多为 python，Linux/CI 多为 python3）
PY=""
for cand in python3 python; do
    if command -v "$cand" >/dev/null 2>&1; then PY="$cand"; break; fi
done

FAILED=0
step() { echo; echo "──────── $1 ────────"; }
fail() { echo "!! $1" >&2; FAILED=1; }

step "[1/4] i18n 一致性"
if bash tools/i18n_check.sh; then
    echo "[ok] i18n 通过"
else
    fail "i18n 未通过"
fi

step "[2/4] 个人数据泄露审查"
if [ -z "$PY" ]; then
    fail "未找到 python，无法运行隐私审计"
elif "$PY" tools/privacy_audit.py; then
    echo "[ok] 未发现会随发布泄露的内容"
else
    fail "隐私审计未通过（见上）"
fi

if [ "$SKIP_BUILD" = "0" ]; then
    step "[3/4] 全量 JVM 单测"
    if ./gradlew testDebugUnitTest --console=plain; then
        echo "[ok] 单测通过"
    else
        fail "单测失败"
    fi

    step "[4/4] release 构建（含 lintVital）"
    if ./gradlew :app:assembleRelease --console=plain; then
        echo "[ok] 构建通过"
    else
        fail "构建失败"
    fi
else
    echo
    echo "（--skip-build：已跳过单测与构建）"
fi

echo
echo "════════════════════════════════════════"
if [ "$FAILED" = "0" ]; then
    echo "发布门禁：全部通过 ✔"
else
    echo "发布门禁：未通过，请先修复上述问题 ✘"
fi
exit "$FAILED"
