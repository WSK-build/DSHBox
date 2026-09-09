#!/usr/bin/env bash
# =============================================================================
#i18n 一致性门禁（
#
# 多语言版本专用静态检查，供 CI 与发版前运行。三项检查：
#   1. 资源键位 parity：各模块（app/common/sandbox-manager）的
#      values/ values-zh/ values-ar/ values-es/ values-fr/ values-ru/
#      六份 strings.xml 键集合（string + plurals）必须完全一致。
#   2. 占位符 parity：同一键在各语言中的占位符（%1$s/%2$d…）多重集必须一致。
#      （用每文件单次 awk 全程扫描，避免 Windows 上逐键派生子进程过慢）
#   3. plurals 复数形态校验：app 模块各语言 <plurals> 的 quantity 集合齐全
#      （en one/other、zh other、es/fr one/other/many、ru one/few/many/other、
#      ar zero/one/two/few/many/other）。
#   4. 源码硬编码扫描：main 源码中引号内 CJK 字符串应清零（注释行剔除）。
#
# 用法：bash tools/i18n_check.sh   （退出码 0 = 通过）
# =============================================================================
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0

# 语言目录顺序：默认(英文)排第一，其余按 BCP-47 排序
LANGS=(values values-zh values-ar values-es values-fr values-ru)
MODULES=(app common sandbox-manager)

extract_keys() { # $1 = strings.xml 路径 → 排序后的键名
  sed -n 's/.*<\(string\|plurals\) name="\([^"]*\)">.*/\2/p' "$1" | sort
}

# 每文件单次 awk：输出 "键名 占位符token...（排序去重）"，按键名排序。
# 占位符按「集合」比较（位置占位符换序是合法的——如中文把源名放前）。
placeholder_map() {
  awk '
    /<(string|plurals) name="/ {
      k=index($0,"name=\""); key=substr($0,k+6); key=substr(key,1,index(key,"\"")-1)
      n=0; inkey=1
    }
    inkey {
      line=$0
      while (match(line, /%[0-9]+\$[a-zA-Z]/)) {
        tok=substr(line, RSTART, RLENGTH); line=substr(line, RSTART+RLENGTH)
        dup=0
        for (i=1; i<=n; i++) if (toks[i] == tok) { dup=1; break }
        if (!dup) { n++; toks[n]=tok }
      }
    }
    inkey && (line ~ /<\/string>/ || line ~ /<\/plurals>/) {
      # 插入排序（n 很小，兼容 mawk/gawk）
      for (i=2; i<=n; i++) { v=toks[i]; j=i-1; while (j>=1 && toks[j] > v) { toks[j+1]=toks[j]; j-- } toks[j+1]=v }
      out=key
      for (i=1; i<=n; i++) out=out " " toks[i]
      print out
      inkey=0
    }
  ' "$1" | sort
}

echo "== [1/4] 键位 parity =="
for m in "${MODULES[@]}"; do
  base="$ROOT/$m/src/main/res"
  [ -d "$base" ] || continue
  ref=""
  bad=""
  for lang in "${LANGS[@]}"; do
    f="$base/$lang/strings.xml"
    if [ ! -f "$f" ]; then
      echo "  ✗ $m/$lang/strings.xml 缺失"; FAIL=1; bad=1; continue
    fi
    keys="$(extract_keys "$f")"
    if [ -z "$ref" ]; then ref="$keys"; continue; fi
    if [ "$keys" != "$ref" ]; then
      [ -z "$bad" ] && echo "  ✗ $m/$lang 与 $m/${LANGS[0]} 键集合不一致"
      diff <(echo "$ref") <(echo "$keys") | head -8
      FAIL=1; bad=1
    fi
  done
  [ -z "$bad" ] && echo "  ✓ $m 六份资源键集合一致"
done

echo "== [2/4] 占位符 parity =="
for m in "${MODULES[@]}"; do
  base="$ROOT/$m/src/main/res"
  [ -d "$base" ] || continue
  ref=""
  bad=""
  for lang in "${LANGS[@]}"; do
    f="$base/$lang/strings.xml"
    [ -f "$f" ] || continue
    map="$(placeholder_map "$f")"
    if [ -z "$ref" ]; then ref="$map"; continue; fi
    if [ "$map" != "$ref" ]; then
      [ -z "$bad" ] && echo "  ✗ $m/$lang 占位符与 ${LANGS[0]} 不一致（键 期望 vs 实际）"
      diff <(echo "$ref") <(echo "$map") | head -8
      FAIL=1; bad=1
    fi
  done
  [ -z "$bad" ] && echo "  ✓ $m 占位符一致"
done

echo "== [3/4] plurals 复数形态校验 =="
# 用 python3 解析 XML（awk match 在本环境对 quantity= 有偏移怪癖，弃用）。
# 校验 app 模块各语言 <plurals> 的 quantity 集合齐全：
#   en one/other、zh other、es/fr one/other/many、ru one/few/many/other、ar 六形态。
py_bin=python3; command -v python3 >/dev/null 2>&1 || py_bin=python
if $py_bin - "$base" <<'PY'
import os, sys
import xml.etree.ElementTree as ET
req = {
  "values": {"one","other"},
  "values-zh": {"other"},
  "values-es": {"one","other","many"},
  "values-fr": {"one","other","many"},
  "values-ru": {"one","few","many","other"},
  "values-ar": {"zero","one","two","few","many","other"},
}
base = sys.argv[1]
fail = False
for lang, must in sorted(req.items()):
    p = os.path.join(base, lang, "strings.xml")
    if not os.path.exists(p):
        continue
    root = ET.parse(p).getroot()
    for el in root:
        if el.tag != "plurals":
            continue
        have = {it.get("quantity") for it in el}
        missing = sorted(must - have)
        if missing:
            print("  ✗ app/%s %s 复数形态缺失: 缺%s 实际[%s]" % (lang, el.get("name"), missing, sorted(have)))
            fail = True
if not fail:
    print("  ✓ app 六语 plurals 形态齐全")
sys.exit(1 if fail else 0)
PY
then
  :
else
  FAIL=1
fi

echo "== [4/4] 源码硬编码 CJK 扫描（引号内） =="
CJK='[\x{4e00}-\x{9fff}]'
HITS=0
for src_dir in \
  "$ROOT/app/src/main" \
  "$ROOT/sandbox-manager/src/main" \
  "$ROOT/common/src/main" \
  "$ROOT/terminal-session/src/main" \
  "$ROOT/bridge/src/main" \
  "$ROOT/terminal-emulator/src/main" \
  "$ROOT/terminal-view/src/main"; do
  [ -d "$src_dir" ] || continue
  # 白名单：ui/theme/AppLocale.kt 中的语言「自称」native name（如 "English"/"中文"/
  # "Français"）是固定专名、不随界面翻译，属设计保留。
  # grep -n 输出带 "绝对路径:行号:" 前缀；先剥离前缀再判注释，
  #否则行首 "/" 会被注释分支误判（修复）。
  while IFS= read -r line; do
    if [[ "$line" == *ui/theme/AppLocale.kt:* ]]; then continue; fi
    t="${line%%//*}"                    # 去掉 // 注释尾巴
    t="$(printf '%s' "$t" | sed 's/^[^:]*:[0-9]*://')"
    t="$(printf '%s' "$t" | sed 's/^[[:space:]]*//')"
    # KDoc/块注释行：空、"/*…"、以 * 开头
    case "$t" in ""|/\**|\**) continue ;; esac
    if printf '%s' "$t" | grep -qP "\"[^\"]*$CJK[^\"]*\""; then
      echo "  ✗ 命中: ${src_dir#$ROOT/}: ${line}"
      HITS=$((HITS+1))
    fi
  done < <(find "$src_dir" -name "*.kt" -type f -exec grep -nP "\"[^\"]*$CJK[^\"]*\"" {} + 2>/dev/null)
done
if [ "$HITS" -eq 0 ]; then echo "  ✓ 无硬编码中文（字符串字面量）"; else FAIL=1; fi

if [ "$FAIL" -eq 0 ]; then
  echo "i18n_check: 全部通过 ✔"
else
  echo "i18n_check: 存在失败项 ✘"
fi
exit "$FAIL"
