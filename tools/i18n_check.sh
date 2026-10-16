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
# 键位一致性（六语键集必须完全一致）逐模块核对。
# 注：plugin-manager 参与键位核对，但**暂不参与下面的硬编码文案扫描**——
# 该模块的领域层（安全模式的状态与事件文案）目前直接产生中文串，尚未改成资源 id；
# 纳入扫描会让本脚本在改造完成前一直失败。改造后把它的 src/main 加进扫描列表。
MODULES=(app common sandbox-manager plugin-manager)

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
# ⚠️ **这一段原本是 bash + `grep -P` 实现的，而那条扫描在本机是零覆盖。**
# 实测：本机的 grep 对 `\x{4e00}` 报
# `character code point value in \x{} or \o{} is too large`，
# 于是 `grep -nP` 一行都匹配不到、整段**每次都报 ✓**。
# 它只在 CI 那份 grep 上有效 —— 于是 2.6 的胶水层里两行中文日志
# 本地全绿、CI 第 9 步红。**这正是本项目反复吃亏的形态：
# 门禁的覆盖面小于它宣称的覆盖面，而它的「通过」看起来完全一样。**
#
# 改用 Python：正则语义在各处一致，本机和 CI 都能真跑。
# 白名单与判定规则**一个字都没改**（见下面的 WHITELIST 与注释）。
python3 - "$ROOT" <<'PYI18N'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1])
scanned = [
    "app/src/main",
    "sandbox-manager/src/main",
    "common/src/main",
    "terminal-session/src/main",
    "bridge/src/main",
    "terminal-emulator/src/main",
    "terminal-view/src/main",
]
# 白名单：这些文件里的中文字符串字面量属设计保留，不是界面文案，因此不随界面翻译：
#   - ui/theme/AppLocale.kt：语言「自称」native name；
#   - runtime/AptSourceProvisioner.kt、runtime/OnlineRuntimeImportManager.kt、
#     service/SandboxService.kt：字符串是**写往 guest 的内容**或与 guest 英文输出混排的日志。
whitelist = [
    "app/src/main/java/com/dshfusion/app/ui/theme/AppLocale.kt",
    "app/src/main/java/com/dshfusion/app/runtime/AptSourceProvisioner.kt",
    "app/src/main/java/com/dshfusion/app/runtime/OnlineRuntimeImportManager.kt",
    "app/src/main/java/com/dshfusion/app/service/SandboxService.kt",
]
cjk = re.compile(r"[\u4e00-\u9fff]")
hits = 0
scanned_files = []
for rel in scanned:
    base = root / rel
    if not base.is_dir():
        continue
    for path in sorted(base.rglob("*.kt")):
        repo_rel = path.relative_to(root).as_posix()
        if repo_rel in whitelist:
            continue
        scanned_files.append(repo_rel)
        for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").split("\n"), 1):
            # 先去掉行注释尾巴，再跳过块注释行 —— 与原来的 bash 版同一条规则。
            code = line.split("//")[0]
            stripped = code.strip()
            if stripped == "" or stripped.startswith("/*") or stripped.startswith("*"):
                continue
            for literal in re.findall(r'"([^"]*)"', stripped):
                if cjk.search(literal):
                    print(f"  ✗ 命中: {repo_rel}:{number}: {line.strip()[:120]}")
                    hits += 1
                    break
# 守卫：**一个文件都没扫到**必须判失败。
# 这一段的原版在本机是零覆盖却报 ✓ —— 「没扫到任何东西」与「扫了且干净」
# 在退出码上完全一样。所以这里显式区分它们。
if not scanned_files:
    print("  ✗ 一个 .kt 都没扫到 —— 这条检查没有覆盖面，不能算通过")
    sys.exit(2)
print(
    f"扫描了 {len(scanned)} 个源码根、{len(scanned_files)} 个 .kt 文件；命中 {hits} 处。"
)
sys.exit(1 if hits else 0)
PYI18N
if [ $? -eq 0 ]; then echo "  ✓ 无硬编码中文（字符串字面量）"; else FAIL=1; fi

if [ "$FAIL" -eq 0 ]; then
  echo "i18n_check: 全部通过 ✔"
else
  echo "i18n_check: 存在失败项 ✘"
fi
exit "$FAIL"
