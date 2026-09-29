#!/usr/bin/env bash
# uninstall.sh — 卸载 mobile-pilot（DSH连接手机）
# 用法: bash uninstall.sh [profile目录]
#
# 顺序与安装相反：**先从 bundles 里摘掉这一条**，再删包目录与命令入口。
# 删除范围以安装时留下的所有权清单为准：有清单时只动清单登记的命令入口与包目录，
# 且命令入口要先认得出是我们的（链接，或内容里带着这个命令名）才删；没有清单
# （旧版装配或手工放置留下的）时只处理两个固定规范路径。不凭"文件内容含某个字符串"
# 在规范路径之外扩大删除面 —— 名字里恰好带这个词的用户文件不该被连坐。
# 不碰 /opt/pilot：那棵树归宿主应用所有，由它自己重铺与回收。
set -e
PROFILE="${1:-}"
if [ -z "$PROFILE" ]; then
  if [ -n "${DSH_HOME:-}" ] && [ -d "$DSH_HOME/profiles/web" ]; then PROFILE="$DSH_HOME/profiles/web"; fi
fi
if [ -z "$PROFILE" ] || [ ! -d "$PROFILE" ]; then
  echo "❌ 找不到 profile。用法: bash uninstall.sh /path/to/.dsh/profiles/web"
  exit 1
fi

PKG="@local/mobile-pilot"
NM="$PROFILE/node_modules"
PKGJSON="$PROFILE/package.json"
PKGDIR="$NM/@local/mobile-pilot"
BIN="$NM/.bin/mobile-pilot"
MANIFEST="$PKGDIR/.mobile-pilot-install.json"
PY="$(command -v python3 || true)"

if [ -f "$PKGJSON" ] && [ -n "$PY" ]; then
  echo "==> 从 bundles 移除 $PKG"
  "$PY" - "$PKGJSON" "$PKG" << 'EOF'
import json, os, sys, tempfile
path, pkg = sys.argv[1], sys.argv[2]
d = json.load(open(path))
bundles = (d.get('dsh', {}).get('profile', {}).get('bundles')) or []
if pkg in bundles:
    d['dsh']['profile']['bundles'] = [b for b in bundles if b != pkg]
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path) or '.')
    with os.fdopen(fd, 'w') as handle:
        json.dump(d, handle, ensure_ascii=False, indent=2)
    os.replace(tmp, path)
    print('   bundles =', d['dsh']['profile']['bundles'])
else:
    print('   (bundles 中未找到,已跳过)')
EOF
elif [ -f "$PKGJSON" ]; then
  echo "❌ 没有 python3，动不了 $PKGJSON；先别删包，否则 bundles 里会留下指向不存在包的条目"
  exit 1
fi

# 先读所有权清单再动包目录：清单本身就在包目录里，删早了就没了。
# 清单是包自带的一份文件，内容可能被改过：binLink 若写成 ../ 一类的相对路径或
# 绝对路径，删除面会越出 profile。落点先归一，确认仍落在 profile 之内才认；
# 越界的条目不按它删（宁可在 stderr 说明，随后只走固定规范路径）。
BIN_TARGET=""
if [ -f "$MANIFEST" ] && [ -n "$PY" ]; then
  BIN_TARGET="$("$PY" - "$MANIFEST" "$PROFILE" << 'EOF'
import json, os, sys
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
try:
    d = json.load(open(sys.argv[1]))
    link = str(d.get('binLink') or '').strip()
except Exception:
    link = ''
if link:
    profile = os.path.abspath(sys.argv[2]).replace(os.sep, '/')
    target = os.path.abspath(os.path.join(sys.argv[2], link)).replace(os.sep, '/')
    if target.startswith(profile + '/'):
        print(target)
    else:
        print(f'! 所有权清单里的 binLink 越出了 profile（{link}），不按它删，改走固定规范路径', file=sys.stderr)
EOF
)"
fi

echo "==> 移除 $PKGDIR"
rm -rf "$PKGDIR"

if [ -n "$BIN_TARGET" ]; then
  TARGET="$BIN_TARGET"
  # 清单说是我们的，也要先认一眼：链接直接算数；普通文件得带着这个命令名才算。
  # 两样都对不上就留着不动 —— 宁可多一句人工确认，不误删碰巧落在同一路径的用户文件。
  if [ -L "$TARGET" ]; then
    rm -f "$TARGET"
    echo "==> 已按所有权清单移除命令入口 $TARGET"
  elif [ -f "$TARGET" ] && grep -q "mobile-pilot" "$TARGET"; then
    rm -f "$TARGET"
    echo "==> 已按所有权清单移除命令入口 $TARGET"
  elif [ -e "$TARGET" ]; then
    echo "! 所有权清单记录的命令入口 $TARGET 认不出是我们的文件（不是链接，内容也对不上），留着没动，请人工确认"
  fi
else
  # 没有清单：只处理固定规范路径，不做任何内容判断。正常形态是符号链接；个别环境里
  # ln 会把链接落成一份复制，ln 失败时安装则整体退出 —— 这里的普通文件多半是这类
  # 落法或人工放置，按同一路径移除即可。
  if [ -L "$BIN" ]; then
    rm -f "$BIN"
    echo "==> 已撤命令链接 $BIN"
  elif [ -f "$BIN" ]; then
    rm -f "$BIN"
    echo "==> 已移除命令入口 $BIN"
  fi
fi

echo "✅ 卸载完成,重启 dsh 生效。"
