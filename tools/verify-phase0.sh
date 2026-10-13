#!/usr/bin/env bash
# 阶段 0 验证：对每个工具跑成功路径与失败路径，记录真实命令、输出与退出码。
#
# 这个脚本存在的理由：口头声称"检查通过"没有证据价值。
# 它把每次验证变成可重复执行、可对照退出码的事实。
#
# 用法：
#   bash tools/verify-phase0.sh            # 在仓库根执行
#   bash tools/verify-phase0.sh 2>&1 | tee verification.log

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PASS=0
FAIL=0
declare -a RESULTS=()

# expect <期望退出码> <描述> <命令...>
expect() {
    local want="$1"; shift
    local desc="$1"; shift
    local out rc
    out="$("$@" 2>&1)"; rc=$?
    if [ "$rc" = "$want" ]; then
        printf '  [PASS] %-52s exit=%s (期望 %s)\n' "$desc" "$rc" "$want"
        PASS=$((PASS + 1))
        RESULTS+=("PASS|$desc|exit=$rc")
    else
        printf '  [FAIL] %-52s exit=%s (期望 %s)\n' "$desc" "$rc" "$want"
        printf '         --- 输出 ---\n'
        printf '%s\n' "$out" | sed 's/^/         /' | head -20
        FAIL=$((FAIL + 1))
        RESULTS+=("FAIL|$desc|exit=$rc 期望=$want")
    fi
}

section() { printf '\n=== %s ===\n' "$1"; }

# 按解释器显式调用工具，不依赖可执行位。
# 理由：ZIP 归档不保留 Unix 权限位，解压后脚本会变成 644，
# 直接执行会得到 exit=126（权限不足）。这是归档格式的限制，不是脚本的问题，
# 所以验证脚本必须自己规避它，而不是要求用户先 chmod。
run_tool() {
    local tool="$1"; shift
    case "$tool" in
        *.sh) bash "$tool" "$@" ;;
        *.py) python3 "$tool" "$@" ;;
        *)    "$tool" "$@" ;;
    esac
}

printf 'DSHFusion 阶段 0 验证\n'
printf '时间: %s\n' "$(date -Iseconds)"
printf '目录: %s\n' "$ROOT"
printf '提交: %s\n' "$(git rev-parse HEAD 2>/dev/null || echo '（非 git 检出）')"

section "1. 契约检查 check-upstreams.py"

expect 0 "正常情况：契约检查通过" \
    run_tool tools/check-upstreams.py
expect 0 "--list 列出检查项" \
    run_tool tools/check-upstreams.py --list

# 失败路径：篡改受保护的许可原件
TAMPER="governance/upstream-licenses/DSHBox/TRADEMARK.md"
cp "$TAMPER" /tmp/phase0-tamper.bak
printf '\n<!-- 验证用篡改 -->\n' >> "$TAMPER"
expect 1 "失败路径：篡改受保护许可原件应失败" \
    run_tool tools/check-upstreams.py
cp /tmp/phase0-tamper.bak "$TAMPER"
rm -f /tmp/phase0-tamper.bak
expect 0 "恢复原件后重新通过" \
    run_tool tools/check-upstreams.py

# 失败路径：破坏 divergences.json 结构
cp governance/divergences.json /tmp/phase0-div.bak
python3 - <<'PY'
import json
from pathlib import Path
p = Path('governance/divergences.json')
d = json.loads(p.read_text(encoding='utf-8'))
d['applied'] = [{"id": "DIV-9999", "title": "坏条目", "type": "非法类型",
                 "target_files": ["tools/does-not-exist.sh"],
                 "change": "x", "rationale": "x", "reapply": "x"}]
p.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding='utf-8')
PY
expect 1 "失败路径：非法 type + 不存在的 applied 文件" \
    run_tool tools/check-upstreams.py
cp /tmp/phase0-div.bak governance/divergences.json
rm -f /tmp/phase0-div.bak
expect 0 "恢复 divergences.json 后重新通过" \
    run_tool tools/check-upstreams.py

section "2. 引用检查 check-references.py"

expect 0 "正常情况：引用检查通过" \
    run_tool tools/check-references.py
expect 0 "--verbose 输出扫描统计" \
    run_tool tools/check-references.py --verbose

# 失败路径：制造一个断链
cp README.md /tmp/phase0-readme.bak
printf '\n[断链测试](docs/this-file-does-not-exist.md)\n' >> README.md
expect 1 "失败路径：Markdown 断链应失败" \
    run_tool tools/check-references.py
cp /tmp/phase0-readme.bak README.md
rm -f /tmp/phase0-readme.bak
expect 0 "恢复 README 后重新通过" \
    run_tool tools/check-references.py

# 失败路径：提到不存在的仓库内路径
# 注意：路径用变量拼出来，避免把字面量写进本文件 ——
# 否则 check-references.py 会把本文件自己也判为断链引用（它确实会，这是正确行为）。
cp README.md /tmp/phase0-readme2.bak
GHOST="tools/no-such""-tool.sh"
printf '\n提到的路径：`%s`\n' "$GHOST" >> README.md
expect 1 "失败路径：不存在的仓库内路径应失败" \
    run_tool tools/check-references.py
cp /tmp/phase0-readme2.bak README.md
rm -f /tmp/phase0-readme2.bak
expect 0 "恢复后重新通过" \
    run_tool tools/check-references.py

section "3. 同步工具 sync-upstream.sh"

expect 1 "失败路径：未知上游 id" \
    run_tool tools/sync-upstream.sh nosuch --report
expect 1 "失败路径：--record 提交号不完整" \
    run_tool tools/sync-upstream.sh dshbox --record abc
expect 1 "失败路径：无参数" \
    run_tool tools/sync-upstream.sh

# 镜像是否存在决定了走哪条路径
MIRRORS="${DSHFUSION_MIRRORS:-$(cd "$ROOT/.." && pwd)/upstream-mirrors}"
if [ -d "$MIRRORS/DSHBox.git" ]; then
    expect 0 "正常路径：上游无新提交（幂等）" \
        run_tool tools/sync-upstream.sh dshbox --report
else
    printf '  [SKIP] 上游镜像不存在（%s），跳过正常路径\n' "$MIRRORS"
    expect 1 "失败路径：镜像缺失应给出可操作的指引" \
        run_tool tools/sync-upstream.sh dshbox --report
fi

section "4. 偏离重放 apply-divergences.sh"

expect 0 "阶段 0：正确报告上游代码尚未导入" \
    run_tool tools/apply-divergences.sh --check

section "5. 静态检查"

expect 0 "所有 shell 脚本语法正确（bash -n）" \
    bash -c 'for f in tools/*.sh; do bash -n "$f" || exit 1; done'
expect 0 "所有 Python 工具可编译" \
    python3 -m compileall -q tools
expect 0 "所有 JSON 可解析" \
    bash -c 'for f in $(find . -name "*.json" -not -path "./.git/*"); do python3 -c "import json,sys; json.load(open(sys.argv[1],encoding=\"utf-8\"))" "$f" || exit 1; done'
expect 0 "受保护许可原件哈希与登记一致" \
    run_tool tools/check-license-hashes.py

section "6. 上游仓库未被修改（只读审计承诺）"

for d in "$ROOT/../audit/DSHA" "$ROOT/../audit/DSHBox"; do
    if [ -d "$d/.git" ]; then
        n=$(git -C "$d" status --porcelain | wc -l)
        if [ "$n" = "0" ]; then
            printf '  [PASS] %-52s 0 个改动\n' "上游工作树干净: $(basename "$d")"
            PASS=$((PASS + 1))
            RESULTS+=("PASS|上游 $(basename "$d") 未被修改|0 改动")
        else
            printf '  [FAIL] %-52s %s 个改动\n' "上游被修改: $(basename "$d")" "$n"
            FAIL=$((FAIL + 1))
            RESULTS+=("FAIL|上游 $(basename "$d") 被修改|$n 改动")
        fi
    else
        printf '  [SKIP] 无审计副本: %s\n' "$d"
    fi
done

section "汇总"
printf '  通过: %d\n' "$PASS"
printf '  失败: %d\n' "$FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf '\n  失败项：\n'
    for r in "${RESULTS[@]}"; do
        case "$r" in FAIL*) printf '    - %s\n' "${r#FAIL|}" ;; esac
    done
    exit 1
fi
printf '\n  全部通过。\n'
exit 0
