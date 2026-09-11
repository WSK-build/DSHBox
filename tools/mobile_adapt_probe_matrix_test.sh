#!/usr/bin/env bash
# 移动端适配插件「启动刷新探测」脚本矩阵测试。
#
# 被测对象：SandboxService.refreshAssembledMobileAdaptPlugin() 里**合并后的**
# 单次 guest 命令（1.3.1 复查 M30）。该脚本用 echo 标记回传三种状态：
#   __DSHBOX_ADAPT_NOT_READY__    未装配 / staged 缺失 → 跳过且不安装
#   __DSHBOX_ADAPT_UP_TO_DATE__   内容一致且 bundle 已注册 → 跳过安装
#   __DSHBOX_ADAPT_NEEDS_INSTALL__  其余 → 继续跑 install.sh
#
# 本脚本在真实 shell 里跑同一模板，逐个 fixture 断言落到的分支。
# 关键回归：**两侧指纹文件同时缺失 + bundle 仍注册**必须判为 NEEDS_INSTALL
# （旧逻辑会因两侧空内容哈希相等而误判 UP_TO_DATE，见 CHANGELOG M27）。
#
# 用法：bash tools/mobile_adapt_probe_matrix_test.sh [--negative-control]
#   --negative-control  故意去掉存在性检查（回到 M27 修复前的逻辑），
#                       用于证明本测试确实能抓住该缺陷，而不是恒绿。

set -u

NEGATIVE=0
[ "${1:-}" = "--negative-control" ] && NEGATIVE=1

FILES="lib/client.js package.json cordis.patch.yml"

# 与 Kotlin 端同构地生成探测脚本（模板唯一，避免测试与生产各写一份）。
probe_script() {
  local plugin_dir="$1" stage_install="$2" stage_plugin="$3" profile_pkg="$4"

  local exists_checks=""
  local f
  if [ "$NEGATIVE" = "0" ]; then
    # 与生产 Kotlin 端一致：全部 test -f 用 " && " 连接（**末尾不带** &&），
    # 再由下面拼装时补一个 " && " 接到哈希比对之前。
    for f in $FILES; do exists_checks="$exists_checks test -f $plugin_dir/$f &&"; done
    for f in $FILES; do exists_checks="$exists_checks test -f $stage_plugin/$f &&"; done
    exists_checks="${exists_checks% &&}"
  fi

  local digest_p="$plugin_dir/lib/client.js $plugin_dir/package.json $plugin_dir/cordis.patch.yml"
  local digest_s="$stage_plugin/lib/client.js $stage_plugin/package.json $stage_plugin/cordis.patch.yml"

  # 拼装指纹条件：有存在性检查时用 " && " 接到哈希比对前；负向对照则直接以哈希比对开头。
  local fingerprint="$exists_checks"
  if [ -n "$fingerprint" ]; then
    fingerprint="$fingerprint && "
  fi
  fingerprint="$fingerprint[ \"\$(cat $digest_p | sha256sum)\" = \"\$(cat $digest_s | sha256sum)\" ] && grep -q '@local/dsh-mobile-adapt' $profile_pkg"

  cat <<EOF
if test -d $plugin_dir && test -f $stage_install; then
  if $fingerprint; then
    echo __DSHBOX_ADAPT_UP_TO_DATE__
  else
    echo __DSHBOX_ADAPT_NEEDS_INSTALL__
  fi
else
  echo __DSHBOX_ADAPT_NOT_READY__
fi
EOF
}

PASS=0
FAIL=0

# 断言某场景落到期望分支。
# 用法：check <场景名> <期望标记> <pluginDir> <stageInstall> <stagePlugin> <profilePkg>
check() {
  local name="$1" expect="$2" plugin_dir="$3" stage_install="$4" stage_plugin="$5" profile_pkg="$6"
  local out
  out="$(probe_script "$plugin_dir" "$stage_install" "$stage_plugin" "$profile_pkg" | sh 2>/dev/null | tr -d '\r')"
  if [ "$out" = "$expect" ]; then
    echo "  ✓ $name → $out"
    PASS=$((PASS + 1))
  else
    echo "  ✗ $name → 期望 $expect，实得 '${out:-（无标记）}'"
    FAIL=$((FAIL + 1))
  fi
}

write_plugin() { # <目录> <内容>
  local d="$1" c="$2" f
  for f in $FILES; do
    mkdir -p "$d/$(dirname "$f")"
    printf '%s' "$c" > "$d/$f"
  done
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

P="$TMP/profile/node_modules/@local/dsh-mobile-adapt"
S="$TMP/stage/plugin"
STAGE_INSTALL="$TMP/stage/install.sh"
PKG="$TMP/profile/package.json"

mkdir -p "$TMP/profile" "$TMP/stage"
printf '{"dsh":{"profile":{"bundles":["@local/dsh-mobile-adapt"]}}}' > "$PKG"
echo '#!/bin/sh' > "$STAGE_INSTALL"
[ -f "$STAGE_INSTALL" ] || { echo "fixture 准备失败：$STAGE_INSTALL 未创建"; exit 1; }

echo "== 移动端适配探测脚本矩阵（negative_control=$NEGATIVE）=="

# ① 未装配：profile 里没有插件目录
mkdir -p "$S"; write_plugin "$S" "same"
check "① 未装配（无插件目录）" "__DSHBOX_ADAPT_NOT_READY__" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ② staged 缺失（install.sh 不在）
check "② staged install.sh 缺失" "__DSHBOX_ADAPT_NOT_READY__" "$P" "$TMP/stage/nope.sh" "$S" "$PKG"

# ③ 已装配且内容一致 → 已是最新
mkdir -p "$P"; write_plugin "$P" "same"
check "③ 内容一致（已是最新）" "__DSHBOX_ADAPT_UP_TO_DATE__" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ④ 内容不同 → 需重装
write_plugin "$P" "old"
check "④ 内容不同（需重装）" "__DSHBOX_ADAPT_NEEDS_INSTALL__" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ⑤ 单侧缺文件 → 需重装
write_plugin "$P" "same"; rm -f "$P/lib/client.js"
check "⑤ 单侧缺文件（需重装）" "__DSHBOX_ADAPT_NEEDS_INSTALL__" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ⑥ 两侧同时缺全部指纹文件 + bundle 仍注册 → 必须需重装（M27 核心回归）
write_plugin "$P" "same"
for f in $FILES; do rm -f "$P/$f" "$S/$f"; done
check "⑥ 两侧全缺 + bundle 已注册" "__DSHBOX_ADAPT_NEEDS_INSTALL__" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ⑦ 内容一致但 bundle 被移除 → 需重装
write_plugin "$P" "same"; write_plugin "$S" "same"
printf '{"dsh":{"profile":{"bundles":[]}}}' > "$PKG"
check "⑦ bundle 被移除（需重装）" "__DSHBOX_ADAPT_NEEDS_INSTALL__" "$P" "$STAGE_INSTALL" "$S" "$PKG"

echo
if [ "$NEGATIVE" = "1" ]; then
  echo "负向对照结果：通过 $PASS / 失败 $FAIL（预期 ⑥ 失败，其余通过）"
else
  echo "结果：通过 $PASS / 失败 $FAIL"
fi
[ "$FAIL" = "0" ] && exit 0 || exit 1
