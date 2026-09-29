#!/usr/bin/env bash
# uninstall.sh — 卸载 dsh-mobile-adapt
# 用法: bash uninstall.sh [profile目录]
set -e
PROFILE="${1:-}"
if [ -z "$PROFILE" ]; then
  if [ -n "$DSH_HOME" ] && [ -d "$DSH_HOME/profiles/web" ]; then PROFILE="$DSH_HOME/profiles/web"; fi
fi
if [ -z "$PROFILE" ] || [ ! -d "$PROFILE" ]; then
  echo "❌ 找不到 profile。用法: bash uninstall.sh /path/to/.dsh/profiles/web"
  exit 1
fi

PKG="@local/dsh-mobile-adapt"
echo "==> 移除 node_modules/$PKG"
rm -rf "$PROFILE/node_modules/$PKG"

echo "==> 从 bundles 移除 $PKG"
python3 - "$PROFILE/package.json" "$PKG" << 'EOF'
import json, sys
path, pkg = sys.argv[1], sys.argv[2]
d = json.load(open(path))
bundles = d.get('dsh', {}).get('profile', {}).get('bundles')
if bundles and pkg in bundles:
    d['dsh']['profile']['bundles'] = [b for b in bundles if b != pkg]
    json.dump(d, open(path, 'w'), ensure_ascii=False, indent=2)
    print('   bundles =', d['dsh']['profile']['bundles'])
else:
    print('   (bundles 中未找到,已跳过)')
EOF

echo "✅ 卸载完成,重启 dsh 生效。"
