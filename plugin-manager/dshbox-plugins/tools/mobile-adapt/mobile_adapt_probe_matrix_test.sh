#!/usr/bin/env bash
# 自有插件包「装配态探测」脚本矩阵测试（mobile-adapt / mobile-pilot 共用同一模板）。
#
# 被测对象：SandboxService.ensureOwnPlugin() 里发往 guest 的那**一条**合并命令。
# 该脚本用 echo 标记回传四种状态：
#   __DSHBOX_PLUGIN_PROFILE_MISSING__  profile 不存在（dsh 从没跑过）→ 跳过，不装
#   __DSHBOX_PLUGIN_STAGE_MISSING__    staged 的 install.sh 缺失 → 跳过，不装
#   __DSHBOX_PLUGIN_UP_TO_DATE__       内容一致且 bundle 已注册 → 跳过，不装
#   __DSHBOX_PLUGIN_NEEDS_INSTALL__    其余 → 跑 install.sh
#
# 本脚本在真实 shell 里跑同一模板，逐个 fixture 断言落到的分支。
# 两条关键回归：
#   · 「插件目录整个不存在」必须判为 NEEDS_INSTALL —— 否则开关只是亮着而 profile 里没有插件；
#   · 「两侧指纹文件同时缺失 + bundle 仍注册」必须判为 NEEDS_INSTALL
#     （两侧空内容哈希相等会误判 UP_TO_DATE，这是要锁住的缺陷）。
#
# 用法：bash plugin-manager/dshbox-plugins/tools/mobile-adapt/mobile_adapt_probe_matrix_test.sh [--negative-control]
#   --negative-control  故意去掉存在性检查（复现修复前的错误逻辑），
#                       用于证明本测试确实能抓住该缺陷，而不是恒绿。

set -u

NEGATIVE=0
[ "${1:-}" = "--negative-control" ] && NEGATIVE=1

# 参与一致性比对的文件：生产端由**暂存包的实际内容**推导，这里给一份同形的清单
# （空壳包的组成：lib/index.js，没有适配包才有的 lib/client.js）。
FILES="lib/index.js package.json cordis.patch.yml"
BUNDLE="@local/mobile-pilot"

# 与 Kotlin 端同构地生成探测脚本（模板唯一，避免测试与生产各写一份）。
probe_script() {
  local profile_dir="$1" plugin_dir="$2" stage_install="$3" stage_plugin="$4" profile_pkg="$5"

  local exists_checks=""
  local f
  if [ "$NEGATIVE" = "0" ]; then
    # 与生产 Kotlin 端一致：全部 test -f 用 " && " 连接（**末尾不带** &&），
    # 再由下面拼装时补一个 " && " 接到哈希比对之前。
    for f in $FILES; do exists_checks="$exists_checks test -f $plugin_dir/$f &&"; done
    for f in $FILES; do exists_checks="$exists_checks test -f $stage_plugin/$f &&"; done
    exists_checks="${exists_checks% &&}"
  fi

  # 摘要列表与存在性检查必须出自**同一份清单**：两者不一致时，
  # 「单侧缺文件」会因为摘要那侧读到的是另一个（两侧都缺的）文件而误判为一致。
  # 生产端由同一个参数生成两者，不存在这种可能。
  local digest_p="" digest_s=""
  for f in $FILES; do
    digest_p="$digest_p $plugin_dir/$f"
    digest_s="$digest_s $stage_plugin/$f"
  done

  # 拼装指纹条件：有存在性检查时用 " && " 接到哈希比对前；负向对照则直接以哈希比对开头。
  local fingerprint="$exists_checks"
  if [ -n "$fingerprint" ]; then
    fingerprint="$fingerprint && "
  fi
  fingerprint="$fingerprint[ \"\$(cat $digest_p | sha256sum)\" = \"\$(cat $digest_s | sha256sum)\" ] && grep -q '$BUNDLE' $profile_pkg"

  cat <<EOF
if ! test -d $profile_dir; then
  echo __DSHBOX_PLUGIN_PROFILE_MISSING__
elif ! test -f $stage_install; then
  echo __DSHBOX_PLUGIN_STAGE_MISSING__
elif $fingerprint; then
  echo __DSHBOX_PLUGIN_UP_TO_DATE__
else
  echo __DSHBOX_PLUGIN_NEEDS_INSTALL__
fi
EOF
}

PASS=0
FAIL=0

# 断言某场景落到期望分支。
# 用法：check <场景名> <期望标记> <profileDir> <pluginDir> <stageInstall> <stagePlugin> <profilePkg>
check() {
  local name="$1" expect="$2" profile_dir="$3" plugin_dir="$4" stage_install="$5" stage_plugin="$6" profile_pkg="$7"
  local out
  out="$(probe_script "$profile_dir" "$plugin_dir" "$stage_install" "$stage_plugin" "$profile_pkg" | sh 2>/dev/null | tr -d '\r')"
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

PROFILE="$TMP/profiles/web"
P="$PROFILE/node_modules/$BUNDLE"
S="$TMP/stage/plugin"
STAGE_INSTALL="$TMP/stage/install.sh"
PKG="$PROFILE/package.json"

mkdir -p "$PROFILE" "$TMP/stage"
printf '{"dsh":{"profile":{"bundles":["%s"]}}}' "$BUNDLE" > "$PKG"
echo '#!/bin/sh' > "$STAGE_INSTALL"
[ -f "$STAGE_INSTALL" ] || { echo "fixture 准备失败：$STAGE_INSTALL 未创建"; exit 1; }

echo "== 自有插件装配探测脚本矩阵（negative_control=$NEGATIVE）=="

# ① profile 目录不存在（dsh 从没跑过）→ 跳过，不替它造家目录
mkdir -p "$S"; write_plugin "$S" "same"
check "① profile 不存在" "__DSHBOX_PLUGIN_PROFILE_MISSING__" "$TMP/nope" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ② 插件目录整个不存在（新装用户）→ 必须判为需安装（本轮核心）
check "② 插件目录不存在（新装）" "__DSHBOX_PLUGIN_NEEDS_INSTALL__" "$PROFILE" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ③ staged 缺失（install.sh 不在）→ 跳过，不装
check "③ staged install.sh 缺失" "__DSHBOX_PLUGIN_STAGE_MISSING__" "$PROFILE" "$P" "$TMP/stage/nope.sh" "$S" "$PKG"

# ④ 已装配且内容一致 → 已是最新
mkdir -p "$P"; write_plugin "$P" "same"
check "④ 内容一致（已是最新）" "__DSHBOX_PLUGIN_UP_TO_DATE__" "$PROFILE" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ⑤ 内容不同 → 需重装
write_plugin "$P" "old"
check "⑤ 内容不同（需重装）" "__DSHBOX_PLUGIN_NEEDS_INSTALL__" "$PROFILE" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ⑥ 单侧缺文件 → 需重装
write_plugin "$P" "same"; rm -f "$P/lib/index.js"
check "⑥ 单侧缺文件（需重装）" "__DSHBOX_PLUGIN_NEEDS_INSTALL__" "$PROFILE" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ⑦ 两侧同时缺全部指纹文件 + bundle 仍注册 → 必须需重装（核心用例）
write_plugin "$P" "same"
for f in $FILES; do rm -f "$P/$f" "$S/$f"; done
check "⑦ 两侧全缺 + bundle 已注册" "__DSHBOX_PLUGIN_NEEDS_INSTALL__" "$PROFILE" "$P" "$STAGE_INSTALL" "$S" "$PKG"

# ⑧ 内容一致但 bundle 被移除 → 需重装
write_plugin "$P" "same"; write_plugin "$S" "same"
printf '{"dsh":{"profile":{"bundles":[]}}}' > "$PKG"
check "⑧ bundle 被移除（需重装）" "__DSHBOX_PLUGIN_NEEDS_INSTALL__" "$PROFILE" "$P" "$STAGE_INSTALL" "$S" "$PKG"

echo
if [ "$NEGATIVE" = "1" ]; then
  echo "负向对照结果：通过 $PASS / 失败 $FAIL（预期 ⑦ 失败，其余通过）"
else
  echo "结果：通过 $PASS / 失败 $FAIL"
fi
[ "$FAIL" = "0" ] && exit 0 || exit 1
