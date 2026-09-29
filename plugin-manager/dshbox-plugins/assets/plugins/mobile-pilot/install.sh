#!/usr/bin/env bash
# install.sh — mobile-pilot（DSH连接手机）一键装配脚本
# 用法: bash install.sh [profile目录] [--pilot-home <信箱挂载点>]
#   默认 profile: $DSH_HOME/profiles/web 或 /workspace/.dsh/profiles/web
#   默认 pilot-home: /opt/pilot（宿主应用绑进沙盒的那个目录）
#
# 与 dsh-mobile-adapt 的 install.sh 同形：复制插件进 profile 的 node_modules，
# 再把它注册进 profile 的 dsh.profile.bundles。这个包多做两件：把信箱挂载点记进
# plugin/mount.json，以及把命令链接进 node_modules/.bin —— 目的都是让 dsh 侧
# 一句 `mobile-pilot ...` 就能用，不必再去沙盒里翻 /opt/pilot。
#
# 三条讲究：
#   1) 先把会被写坏的东西全查一遍（python3 在不在、插件内容齐不齐、profile 结构对不对、
#      .bin/mobile-pilot 是不是个真目录），再动手写。
#   2) 复制先落阶段目录，语法与文件清单在阶段目录上验过，才动活跃包：旧包整体挪进备份位，
#      新包改名上位；发布之后任何一步失败，把半包撤掉、旧包放回原位 —— 升级失败的旧版
#      依然可用。首装（没有旧版）失败时，连本次登记的 bundle 条目与命令链接一并摘除，
#      不给 loader 留指向不存在包的条目。起步时还先自愈上次异常退出留下的残留。
#      注意这条链路不是原子操作：旧包让位到新包上位之间有一个不完整的窗口，
#      重装或升级前先停 dsh，装完再启动。
#   3) 改用户 profile 的 package.json 一律"写临时文件 + 原子改名"，不就地截断它。
set -e

# --- 定位 profile ---
PROFILE=""
PILOT_HOME="/opt/pilot"
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --pilot-home)
      if [ $# -lt 2 ] || [ -z "$2" ]; then echo "❌ --pilot-home 后面要跟一个路径"; exit 1; fi
      PILOT_HOME="$2"; shift 2 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
[ ${#ARGS[@]} -gt 0 ] && PROFILE="${ARGS[0]}"

if [ -z "$PROFILE" ]; then
  if [ -n "${DSH_HOME:-}" ] && [ -d "$DSH_HOME/profiles/web" ]; then
    PROFILE="$DSH_HOME/profiles/web"
  elif [ -d "/workspace/.dsh/profiles/web" ]; then
    PROFILE="/workspace/.dsh/profiles/web"
  elif [ -d "/root/projects/.dsh/profiles/web" ]; then
    # 沙盒里的工程根有两种落法：/workspace 与 /root/projects。两处都试，取存在的那一个。
    PROFILE="/root/projects/.dsh/profiles/web"
  fi
fi
if [ -z "$PROFILE" ] || [ ! -d "$PROFILE" ]; then
  echo "❌ 找不到 dsh profile 目录。请手动指定: bash install.sh /path/to/.dsh/profiles/web"
  exit 1
fi

PKG="@local/mobile-pilot"
SRC="$(cd "$(dirname "$0")" && pwd)/plugin"
NM="$PROFILE/node_modules"
PKGJSON="$PROFILE/package.json"
BINLINK="$NM/.bin/mobile-pilot"
# 必需文件清单：安装源、阶段目录与发布结果各核一遍。装包之前先知道"装齐了长什么样"，
# 卸载与回滚才有凭据 —— 这份清单同时写进所有权清单（见第 7 步）。
REQUIRED="package.json cordis.patch.yml lib/index.js lib/mount.js lib/pilot.js lib/tools.js bin/mobile-pilot"

# --- 0) 先验：任何一项不过都不写东西 ---
PY="$(command -v python3 || true)"
if [ -z "$PY" ]; then
  echo "❌ 注册 bundle 要改 profile 的 package.json，这一步靠 python3；沙盒里没有就先装，别在半途退出"
  exit 1
fi
for f in "$SRC/package.json" "$SRC/cordis.patch.yml" "$SRC/lib/index.js" "$SRC/lib/mount.js" "$SRC/lib/pilot.js" "$SRC/lib/tools.js" "$SRC/bin/mobile-pilot"; do
  [ -f "$f" ] || { echo "❌ 插件内容不完整，缺 $f"; exit 1; }
done
[ -f "$PKGJSON" ] || { echo "❌ 找不到 $PKGJSON (profile 结构不兼容)"; exit 1; }
"$PY" - "$PKGJSON" << 'EOF'
import json, sys
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
d = json.load(open(sys.argv[1]))
bundles = d.get('dsh', {}).get('profile', {}).get('bundles')
if not isinstance(bundles, list):
    sys.exit('❌ package.json 缺少 dsh.profile.bundles,请确认 profile 结构(需 dsh 0.1.x)')
EOF
if [ -e "$BINLINK" ] && [ ! -L "$BINLINK" ] && [ -d "$BINLINK" ]; then
  echo "❌ $BINLINK 是一个真目录，不是链接。请人工处理，我不替你删目录"
  exit 1
fi

# --- 0.5) 自愈上次异常退出留下的残留 ---
# 活跃包缺失而备份位还在：上次发布后没来得及把备份放回原位（安装被打断）。
# 先复位再装，否则这次安装会把旧版当作不存在，回滚时也没有旧版可回。
if [ ! -e "$NM/$PKG" ]; then
  for residue in "$NM/@local"/.mobile-pilot-backup.*; do
    [ -e "$residue" ] || continue
    echo "! 活跃包缺失但发现上次遗留的备份 $residue，先复位到活跃位"
    if mv "$residue" "$NM/$PKG"; then
      break
    fi
    echo "❌ 备份复位失败：请人工处理 $residue 后重试"
    exit 1
  done
fi

# --- 事务骨架：阶段目录 → 校验 → 备份 → 替换 → 失败回滚 ---
# 脚本名带 $$：同机两次安装不会踩对方的暂存位。进程号复用可能撞上上次没收走的
# 事务目录：cp/mv 会把内容塞进旧目录里而不是替换它，所以占用时换名重试一次，
# 仍占用就停下来让人工处理，不带着可疑的现场继续写。
STAGE="$NM/@local/.mobile-pilot-stage.$$"
BACKUP="$NM/@local/.mobile-pilot-backup.$$"
if [ -e "$STAGE" ]; then
  STAGE="$STAGE.retry"
  if [ -e "$STAGE" ]; then
    echo "❌ 阶段目录 $STAGE 仍被上次安装的残留占用，请人工清理后重试"
    exit 1
  fi
fi
if [ -e "$BACKUP" ]; then
  BACKUP="$BACKUP.retry"
  if [ -e "$BACKUP" ]; then
    echo "❌ 备份位 $BACKUP 仍被上次安装的残留占用，请人工清理后重试"
    exit 1
  fi
fi
PUBLISHED=""
LINK_ADDED=""

# 从 profile 的 package.json 摘掉本包的 bundle 条目（临时文件 + 原子改名）。
# 只在首装回滚时调用：那时条目指向的包即将不存在，留着它会让 loader 在启动期
# 撞上"包不存在"。条目本就不在清单里时是无害的空操作。
remove_bundle_entry () {
  "$PY" - "$PKGJSON" "$PKG" << 'EOF'
import json, os, sys, tempfile
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
path, pkg = sys.argv[1], sys.argv[2]
d = json.load(open(path))
profile = d.get('dsh', {}).get('profile', {})
bundles = profile.get('bundles')
if isinstance(bundles, list) and pkg in bundles:
    profile['bundles'] = [b for b in bundles if b != pkg]
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path) or '.')
    with os.fdopen(fd, 'w') as handle:
        json.dump(d, handle, ensure_ascii=False, indent=2)
    os.replace(tmp, path)
EOF
}

cleanup () {
  local rc=$?
  trap - EXIT INT TERM
  if [ "$rc" -ne 0 ]; then
    if [ -n "$PUBLISHED" ] && [ ! -e "$BACKUP" ]; then
      # 首装失败：没有旧版可回。半包撤掉之后，bundle 条目与命令链接都会悬空
      #（指向一个即将不存在的包）—— 留着就是给 loader 埋启动期错误，一并摘除。
      # 条目是否本次新增不必区分：活跃包在本轮开始前就不存在，任何指向它的条目
      # 在回滚后都是悬空的。回滚各步逐一兜底，前一步失败不吞掉后面的步骤；
      # 哪一步真失败了就在 stderr 点名残留位置——那句"回到安装前"不许变成假陈述。
      rollback_note=""
      rm -rf "$NM/$PKG" || { rollback_note="$rollback_note
⚠ 残留未清：包目录 $NM/$PKG 仍存在，请手动删除"; }
      remove_bundle_entry || { rollback_note="$rollback_note
⚠ 残留未清：bundle 条目摘除失败，请手动检查 $PKGJSON"; }
      if [ -n "$LINK_ADDED" ]; then
        if [ -L "$BINLINK" ]; then
          local linkTarget
          linkTarget="$(readlink "$BINLINK" 2>/dev/null || true)"
          # 只撤指向本包的链接：碰巧同名、却指着别处的用户文件不动。
          case "$linkTarget" in
            *@local/mobile-pilot/bin/mobile-pilot) rm -f "$BINLINK" || { rollback_note="$rollback_note
⚠ 残留未清：命令链接 $BINLINK 删除失败，请手动检查"; } ;;
          esac
        elif [ -f "$BINLINK" ]; then
          # 个别环境里 ln 会把链接落成一份复制：这份是本轮刚写出的，一并收回。
          rm -f "$BINLINK" || { rollback_note="$rollback_note
⚠ 残留未清：复制形态的命令入口 $BINLINK 删除失败，请手动检查"; }
        fi
      fi
      echo "❌ 安装失败，已回滚：本次是首装，半包、bundle 条目与命令链接都已摘除，profile 回到安装前的状态" >&2
      [ -n "$rollback_note" ] && printf '%s\n' "$rollback_note" >&2
    else
      # 升级失败（或失败发生在发布之前）：旧包复位后，bundle 条目与命令链接
      # 照旧指向它，条目保留是正确状态，不摘。
      if [ -n "$PUBLISHED" ]; then
        rm -rf "$NM/$PKG" || true
      fi
      if [ -e "$BACKUP" ]; then
        mv "$BACKUP" "$NM/$PKG" || true
        echo "❌ 安装失败，已回滚：$NM/$PKG 恢复为上一版本，旧版保持可用" >&2
      fi
    fi
  elif [ -n "$BACKUP" ] && [ -e "$BACKUP" ]; then
    rm -rf "$BACKUP" || true
  fi
  if [ -n "$STAGE" ]; then
    rm -rf "$STAGE" || true
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 清单核对：七个必需文件齐全且非空。空文件比缺文件更阴险 —— loader 装载时才炸。
check_inventory () {
  local root="$1" f
  for f in $REQUIRED; do
    if [ ! -s "$root/$f" ]; then
      echo "❌ 文件清单核对没过：$root/$f 缺失或为空"
      return 1
    fi
  done
}

# --- 1) 复制到阶段目录：校验全部在暂存位上做，不过就不动活跃包 ---
mkdir -p "$NM/@local"
echo "==> 复制插件到阶段目录 $STAGE"
cp -r "$SRC" "$STAGE"

NODE="$(command -v node || true)"
if [ -n "$NODE" ]; then
  # bin/mobile-pilot 也在列：它是 dsh 侧唯一能跑的调试通路，语法错会表现为"包装上了但什么都调不通"。
  # 别把 install.sh / uninstall.sh 加进来 —— 那是 sh，会被按 JS 判成语法错。
  for f in "$STAGE/lib/index.js" "$STAGE/lib/mount.js" "$STAGE/lib/pilot.js" "$STAGE/lib/tools.js" "$STAGE/bin/mobile-pilot"; do
    "$NODE" --check "$f" || { echo "❌ JS 语法检查没过：$f"; exit 1; }
  done
else
  echo "! 沙盒里没有 node，跳过 JS 语法预检；工具一条都不出现时先往这一处查"
fi
check_inventory "$STAGE"

# --- 2) 旧包让位：整体挪进备份位，内容一字不动 ---
if [ -e "$NM/$PKG" ]; then
  echo "==> 旧包挪进备份位 $BACKUP"
  mv "$NM/$PKG" "$BACKUP"
fi

# --- 3) 发布：阶段目录改名上位，再对发布结果复检一遍清单 ---
echo "==> 发布到 $NM/$PKG"
mv "$STAGE" "$NM/$PKG"
PUBLISHED=1
check_inventory "$NM/$PKG"
chmod +x "$NM/$PKG/bin/mobile-pilot"

# --- 4) 记下信箱挂载点（此刻不在也要记，doctor 会照实报）---
echo "==> 记录信箱挂载点: $PILOT_HOME"
"$PY" - "$NM/$PKG/mount.json" "$PILOT_HOME" << 'EOF'
import json, os, sys, tempfile
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
target, home = sys.argv[1], sys.argv[2]
payload = {
    'pilotHome': os.path.abspath(home),
    'entryPresent': os.path.isfile(os.path.join(home, 'bin', 'pilot')),
    'manifestPresent': os.path.isfile(os.path.join(home, 'capabilities.json')),
}
fd, tmp = tempfile.mkstemp(dir=os.path.dirname(target) or '.')
with os.fdopen(fd, 'w') as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
os.replace(tmp, target)
if not payload['entryPresent']:
    print('   ⚠ 此刻没有', os.path.join(home, 'bin', 'pilot'), '—— 装配可以排在它之前，宿主铺好信箱后这条路径就会亮')
EOF

# --- 5) 注册进 profile bundles ---
echo "==> 注册 bundle: $PKG"
"$PY" - "$PKGJSON" "$PKG" << 'EOF'
import json, os, sys, tempfile
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
path, pkg = sys.argv[1], sys.argv[2]
d = json.load(open(path))
bundles = d['dsh']['profile']['bundles']
if pkg not in bundles:
    bundles.append(pkg)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path) or '.')
    with os.fdopen(fd, 'w') as handle:
        json.dump(d, handle, ensure_ascii=False, indent=2)
    os.replace(tmp, path)
print('   bundles =', bundles)
EOF

# --- 6) 命令链接 ---
mkdir -p "$NM/.bin"
# 这一步只做一次 ln：能建符号链接就是链接；个别环境会把它落成一份复制（照常记入
# LINK_ADDED，回滚与卸载按同一路径回收）；ln 真正失败（如无链接权限）时安装整体
# 退出、已发布的包由 cleanup 回滚 —— 脚本里不再另做一层复制降级。
if ! ln -sfn "../$PKG/bin/mobile-pilot" "$BINLINK"; then
  echo "❌ 建立命令链接失败（无链接权限时安装退出，不做复制降级）: $BINLINK"
  exit 1
fi
LINK_ADDED=1
echo "==> 命令链接: $BINLINK"

# --- 7) 所有权清单：卸载只删这里登记过的东西，不凭文件内容猜归属 ---
echo "==> 写所有权清单"
"$PY" - "$NM/$PKG/.mobile-pilot-install.json" "$BINLINK" $REQUIRED << 'EOF'
import json, os, sys, tempfile, time
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
target, binlink = sys.argv[1], sys.argv[2]
files = sys.argv[3:]
# target 在 <profile>/node_modules/@local/<pkg>/ 之下：向上四级才是 profile 根。
profile = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(target)))))
payload = {
    'package': '@local/mobile-pilot',
    'binLink': os.path.relpath(os.path.abspath(binlink), profile).replace(os.sep, '/'),
    'files': files,
    'installedAtWall': int(time.time()),
}
fd, tmp = tempfile.mkstemp(dir=os.path.dirname(target) or '.')
with os.fdopen(fd, 'w') as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
os.replace(tmp, target)
EOF

echo
echo "+ mobile-pilot 装配完成。请重启 dsh 生效；沙盒里可直接 mobile-pilot doctor / ui / capabilities / call。"
echo "+ 包的替换不是原子操作（阶段目录→校验→备份→替换）：重装或升级前先停 dsh，装完再启动。"
echo "+ 重启后 agent 的工具列表里会多出 phone_* 工具（phone_status / phone_observe / phone_click / phone_setValue /"
echo "  phone_scroll / phone_node / phone_waitFor / phone_key / phone_launch / phone_capture / phone_intent /"
echo "  phone_surface / phone_call）。"
