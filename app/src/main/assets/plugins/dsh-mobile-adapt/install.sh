#!/usr/bin/env bash
# install.sh — dsh-mobile-adapt 一键装配脚本
# 用法: bash install.sh [profile目录]
#   默认 profile: $DSH_HOME/profiles/web 或 /workspace/.dsh/profiles/web
set -e

# --- 定位 profile ---
PROFILE="${1:-}"
if [ -z "$PROFILE" ]; then
  if [ -n "$DSH_HOME" ] && [ -d "$DSH_HOME/profiles/web" ]; then
    PROFILE="$DSH_HOME/profiles/web"
  elif [ -d "/workspace/.dsh/profiles/web" ]; then
    PROFILE="/workspace/.dsh/profiles/web"
  fi
fi
if [ -z "$PROFILE" ] || [ ! -d "$PROFILE" ]; then
  echo "❌ 找不到 dsh profile 目录。请手动指定: bash install.sh /path/to/.dsh/profiles/web"
  exit 1
fi

PKG="@local/dsh-mobile-adapt"
SRC="$(cd "$(dirname "$0")" && pwd)/plugin"

# --- 1) 复制插件到 profile node_modules ---
NM="$PROFILE/node_modules"
mkdir -p "$NM/@local"
echo "==> 复制插件到 $NM/$PKG"
rm -rf "$NM/$PKG"
cp -r "$SRC" "$NM/$PKG"

# --- 2) 注册进 profile bundles ---
PKGJSON="$PROFILE/package.json"
if [ ! -f "$PKGJSON" ]; then
  echo "❌ 找不到 $PKGJSON (profile 结构不兼容)"
  exit 1
fi
echo "==> 注册 bundle: $PKG"
python3 - "$PKGJSON" "$PKG" << 'EOF'
import json, sys
path, pkg = sys.argv[1], sys.argv[2]
d = json.load(open(path))
bundles = d.get('dsh', {}).get('profile', {}).get('bundles')
if bundles is None:
    print('❌ package.json 缺少 dsh.profile.bundles,请确认 profile 结构(需 dsh 0.1.x)')
    sys.exit(1)
if pkg not in bundles:
    bundles.append(pkg)
    d['dsh']['profile']['bundles'] = bundles
    json.dump(d, open(path, 'w'), ensure_ascii=False, indent=2)
print('   bundles =', bundles)
EOF

echo
echo "+ dsh-mobile-adapt 装配完成。请重启 dsh 生效。"
