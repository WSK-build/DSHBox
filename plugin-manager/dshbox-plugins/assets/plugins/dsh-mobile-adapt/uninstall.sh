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
import json, os, sys, tempfile
path, pkg = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    d = json.load(handle)
bundles = d.get('dsh', {}).get('profile', {}).get('bundles')
changed = False
if bundles and pkg in bundles:
    d['dsh']['profile']['bundles'] = [b for b in bundles if b != pkg]
    changed = True
# 同时摘掉依赖声明：装着时 install.sh 写了 dependencies[<pkg>] = file:<暂存目录>。
# 只删目录不摘声明的话，下一次任何 pnpm 安装都会按 manifest 把它重新装回来
# （磁盘上复活、但不在 bundles 所以不加载），与"已卸载"自相矛盾。
deps = d.get('dependencies')
if isinstance(deps, dict) and pkg in deps:
    deps.pop(pkg, None)
    changed = True
if changed:
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path) or '.')
    with os.fdopen(fd, 'w', encoding='utf-8') as handle:
        json.dump(d, handle, ensure_ascii=False, indent=2)
        handle.write('\n')
    os.replace(tmp, path)
    # 取值用 .get 链：写入已经成功，这里再因缺键抛异常会让已生效的卸载被报成失败。
    print('   bundles =', (d.get('dsh') or {}).get('profile', {}).get('bundles'))
else:
    print('   (bundles 中未找到,已跳过)')
EOF

echo "✅ 卸载完成,重启 dsh 生效。"
