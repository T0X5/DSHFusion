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

# 备份文件放一次性临时目录，不写死 /tmp 路径。
# 写死的问题不是洁癖：并发跑两次套件会互相覆盖备份，而且中断后残留文件
# 会被发布前的隐私审计当成「本机痕迹」报出来。
SCRATCH="$(mktemp -d)"
cleanup() { rm -rf "$SCRATCH"; }
trap cleanup EXIT INT TERM
cd "$ROOT" || exit 1

PASS=0
FAIL=0
SKIP=0
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

# ok/fail：给「条件式」断言用的计数器包装。
#
# 为什么需要单独一对：expect 只能测「某个命令的退出码等于某个值」。
# 而有些断言是「读了输出再判断」（比如「注入后必须报出这条具体原因，
# 而不是碰巧退出 1」），那种必须自己 if/else。
#
# ⚠️ 这两个函数曾经**不存在**，而调用它们的地方照样「通过」——
# 因为本脚本没有 set -e，`ok: command not found` 只会打到 stderr，
# 不改变退出码，断言就静默变成了空操作。那次事故让 5d、5e 两节共 11 项
# 断言全部没执行，而套件仍然报「全部通过」。
# 教训：**检查器本身必须有测试**，否则它会安静地什么都不查。
# 现在 5d/5e/5f 用的就是这两个函数，且套件总数会随它们变化。
ok() {
    printf '  [PASS] %s\n' "$1"
    PASS=$((PASS + 1))
    RESULTS+=("PASS|$1|ok")
}

fail() {
    printf '  [FAIL] %s\n' "$1"
    FAIL=$((FAIL + 1))
    RESULTS+=("FAIL|$1|fail")
}

# skip：条件不满足、无法构造该场景时用。
#
# **「跳过」不等于「通过」** —— 它单独计数，汇总里会如实报出跳过多少项。
# 这条规则来自本仓库的硬约束「未运行的检查写『未运行』，不写『通过』」：
# 把跳过混进通过数，等于让一个从没跑过的断言看起来是绿的。
skip() {
    printf '  [SKIP] %s\n' "$1"
    SKIP=$((SKIP + 1))
    RESULTS+=("SKIP|$1|skip")
}

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
cp "$TAMPER" "$SCRATCH/tamper.bak"
printf '\n<!-- 验证用篡改 -->\n' >> "$TAMPER"
expect 1 "失败路径：篡改受保护许可原件应失败" \
    run_tool tools/check-upstreams.py
cp "$SCRATCH/tamper.bak" "$TAMPER"
rm -f "$SCRATCH/tamper.bak"
expect 0 "恢复原件后重新通过" \
    run_tool tools/check-upstreams.py

# 失败路径：破坏 divergences.json 结构
cp governance/divergences.json "$SCRATCH/div.bak"
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
cp "$SCRATCH/div.bak" governance/divergences.json
rm -f "$SCRATCH/div.bak"
expect 0 "恢复 divergences.json 后重新通过" \
    run_tool tools/check-upstreams.py

section "2. 引用检查 check-references.py"

expect 0 "正常情况：引用检查通过" \
    run_tool tools/check-references.py
expect 0 "--verbose 输出扫描统计" \
    run_tool tools/check-references.py --verbose

# 失败路径：制造一个断链
cp README.md "$SCRATCH/readme.bak"
printf '\n[断链测试](docs/this-file-does-not-exist.md)\n' >> README.md
expect 1 "失败路径：Markdown 断链应失败" \
    run_tool tools/check-references.py
cp "$SCRATCH/readme.bak" README.md
rm -f "$SCRATCH/readme.bak"
expect 0 "恢复 README 后重新通过" \
    run_tool tools/check-references.py

# 失败路径：提到不存在的仓库内路径
# 注意：路径用变量拼出来，避免把字面量写进本文件 ——
# 否则 check-references.py 会把本文件自己也判为断链引用（它确实会，这是正确行为）。
cp README.md "$SCRATCH/readme2.bak"
GHOST="tools/no-such""-tool.sh"
printf '\n提到的路径：`%s`\n' "$GHOST" >> README.md
expect 1 "失败路径：不存在的仓库内路径应失败" \
    run_tool tools/check-references.py
cp "$SCRATCH/readme2.bak" README.md
rm -f "$SCRATCH/readme2.bak"
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

section "3b. --record 校验（在隔离夹具上跑，不动真实仓库）"

# --record 是唯一会改 governance/upstreams.json 的动作。它的失败路径在真实仓库上
# 无法安全制造（真实镜像 HEAD 就是锁定提交），所以在临时夹具上测。
FIXTURE="$(mktemp -d)"
if bash tools/make-record-fixture.sh "$FIXTURE" >/dev/null 2>&1; then
    # shellcheck disable=SC1091
    . "$FIXTURE/commits.env"
    export DSHFUSION_MIRRORS="$FIXTURE/mirrors"

    # 注意：不能放进子 shell —— expect 在子 shell 里累加 PASS/FAIL 会丢失。
    # 用保存/恢复目录代替。
    ORIG_PWD="$PWD"
    cd "$FIX_REPO" || exit 1

    expect 1 "校验1：提交不存在于镜像" \
        bash tools/sync-upstream.sh dshbox --record 0000000000000000000000000000000000000000
    expect 1 "校验1：对象不是 commit（tree 的 SHA）" \
        bash tools/sync-upstream.sh dshbox --record "$(git -C "$FIX_MIRROR" rev-parse "$FIX_C3^{tree}")"
    expect 1 "校验1：非十六进制提交号" \
        bash tools/sync-upstream.sh dshbox --record zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz
    expect 1 "校验2：提交不属于默认分支历史" \
        bash tools/sync-upstream.sh dshbox --record "$FIX_SIDE"
    expect 1 "校验3：锁定提交不是新提交的祖先" \
        bash tools/sync-upstream.sh dshbox --record "$FIX_ORPHAN"
    expect 1 "校验3b：关系回退（rewound 在 main 上但非后代）" \
        bash tools/sync-upstream.sh dshbox --record "$FIX_REWOUND"
    expect 1 "校验3c：就是当前锁定提交" \
        bash tools/sync-upstream.sh dshbox --record "$FIX_C3"
    expect 1 "校验4a：缺审核记录" \
        bash tools/sync-upstream.sh dshbox --record "$FIX_AHEAD"

    # 生成摘要后补齐记录，逐级验证 4b/4c/4d/成功
    bash tools/sync-upstream.sh dshbox --report >/dev/null 2>&1 || true
    REC="$(ls docs/upstream-reports/*dshbox*.md 2>/dev/null | head -1)"

    if [ -n "$REC" ]; then
        # 4b：有摘要文件但未写 record_commit
        expect 1 "校验4b：摘要存在但无 record_commit" \
            bash tools/sync-upstream.sh dshbox --record "$FIX_AHEAD"

        # 4c：integration=rejected
        cat >> "$REC" <<EOF

## 审核记录

\`\`\`yaml
record_commit: $FIX_AHEAD
upstream: dshbox
range: $FIX_C3..$FIX_AHEAD
integration: rejected
tests: fixture
\`\`\`
EOF
        expect 1 "校验4c：integration=rejected 时拒绝推进" \
            bash tools/sync-upstream.sh dshbox --record "$FIX_AHEAD"

        # 4d：范围声明错配
        sed -i 's/^integration: rejected$/integration: passed/' "$REC"
        sed -i 's|^range: .*$|range: deadbeef..deadbeef|' "$REC"
        expect 1 "校验4d：审核记录范围与实际错配" \
            bash tools/sync-upstream.sh dshbox --record "$FIX_AHEAD"

        # 正常路径：全部校验通过，推进锁定提交并留下审计痕迹
        sed -i "s|^range: .*\$|range: $FIX_C3..$FIX_AHEAD|" "$REC"
        expect 0 "正常路径：4 项校验通过后推进 locked_commit" \
            bash tools/sync-upstream.sh dshbox --record "$FIX_AHEAD"

        # 确认审计痕迹确实写入
        if python3 -c "
import json
d=json.load(open('governance/upstreams.json'))
e=[u for u in d['upstreams'] if u['id']=='dshbox'][0]
h=e.get('locked_commit_history') or []
assert e['locked_commit']=='$FIX_AHEAD', 'locked_commit 未更新'
assert h and h[-1]['commit']=='$FIX_AHEAD', '未写入 locked_commit_history'
assert h[-1]['previous']=='$FIX_C3', 'previous 记录错误'
"; then
            printf '  [PASS] %-52s\n' "成功路径留下 locked_commit_history 审计痕迹"
            PASS=$((PASS + 1))
            RESULTS+=("PASS|locked_commit_history 审计痕迹|ok")
        else
            printf '  [FAIL] %-52s\n' "成功路径留下 locked_commit_history 审计痕迹"
            FAIL=$((FAIL + 1))
            RESULTS+=("FAIL|locked_commit_history 审计痕迹|缺失或不正确")
        fi
    else
        printf '  [FAIL] %-52s\n' "夹具摘要生成失败，校验 4 未覆盖"
        FAIL=$((FAIL + 1))
        RESULTS+=("FAIL|夹具摘要生成|失败")
    fi

    cd "$ORIG_PWD" || exit 1
    unset DSHFUSION_MIRRORS
else
    printf '  [FAIL] %-52s\n' "无法建立 --record 测试夹具"
    FAIL=$((FAIL + 1))
    RESULTS+=("FAIL|--record 测试夹具|建立失败")
fi
rm -rf "$FIXTURE"

section "4. 偏离重放 apply-divergences.sh"

expect 0 "阶段 0：正确报告上游代码尚未导入" \
    run_tool tools/apply-divergences.sh --check

section "4b. 偏离重放：施加逻辑与失败路径（隔离夹具）"

# 这些场景在真实仓库上无法制造（会污染主干或根本不存在），因此在夹具上测。
DV_ORIG="$PWD"
for scenario in normal drift asset-missing strings-moved brand-intact brand-missing unknown-div; do
    DV_DIR="$(mktemp -d)"
    if bash tools/make-divergence-fixture.sh "$DV_DIR" "$scenario" >/dev/null 2>&1; then
        cd "$DV_DIR" || exit 1
        case "$scenario" in
            normal)
                expect 0 "场景 normal：全部偏离可施加" \
                    bash tools/apply-divergences.sh
                expect 0 "场景 normal：再次运行幂等（全部已生效）" \
                    bash tools/apply-divergences.sh --check
                # 正向确认修改真的落盘
                if grep -q 'applicationId = "com.dshfusion.app"' app/build.gradle.kts \
                   && grep -q 'DSHFUSION_RUNTIME_ASSETS' app/build.gradle.kts \
                   && grep -q 'app_name">DSHFusion' app/src/main/res/values/strings.xml \
                   && ! grep -rq 'WSK-build' app/src/main/java/com/dshbox/app/util/AppVersion.kt; then
                    printf '  [PASS] %-52s\n' "场景 normal：改动确实写入文件"
                    PASS=$((PASS + 1)); RESULTS+=("PASS|normal 改动落盘|ok")
                else
                    printf '  [FAIL] %-52s\n' "场景 normal：改动确实写入文件"
                    FAIL=$((FAIL + 1)); RESULTS+=("FAIL|normal 改动落盘|未生效")
                fi
                # 品牌图形核验必须真的跑过并给出结论（不能只改 app_name 就报成功）
                NORMAL_OUT="$(bash tools/apply-divergences.sh --check 2>&1 || true)"
                if printf '%s' "$NORMAL_OUT" | grep -q '品牌图形 8 个均已替换'; then
                    printf '  [PASS] %-52s\n' "场景 normal：品牌图形 8 个核验通过"
                    PASS=$((PASS + 1)); RESULTS+=("PASS|normal 品牌图形|ok")
                else
                    printf '  [FAIL] %-52s\n' "场景 normal：品牌图形 8 个核验通过"
                    FAIL=$((FAIL + 1)); RESULTS+=("FAIL|normal 品牌图形|未核验")
                fi
                ;;
            drift)
                expect 1 "场景 drift：applicationId 写法变化应报失败" \
                    bash tools/apply-divergences.sh
                ;;
            asset-missing)
                expect 1 "场景 asset-missing：固定字符串未匹配应报失败" \
                    bash tools/apply-divergences.sh
                ;;
            strings-moved)
                expect 1 "场景 strings-moved：目标条目消失应报失败（非静默通过）" \
                    bash tools/apply-divergences.sh
                ;;
            brand-intact)
                expect 1 "场景 brand-intact：品牌图形仍为上游资产应报失败" \
                    bash tools/apply-divergences.sh
                # 报错必须点名具体文件，否则无法据以人工替换。
                # 注意：这里必须先把输出捕获下来再匹配 —— 套件开了 pipefail，
                # 而本场景脚本本来就以 1 退出，直接 `脚本 | grep` 会让整条管道
                # 返回非零，即使 grep 命中也会判假。
                BRAND_OUT="$(bash tools/apply-divergences.sh 2>&1 || true)"
                if printf '%s' "$BRAND_OUT" | grep -q '品牌图形仍为上游资产 8/8 个'; then
                    printf '  [PASS] %-52s\n' "场景 brand-intact：报错点名了具体文件"
                    PASS=$((PASS + 1)); RESULTS+=("PASS|brand-intact 报错明细|ok")
                else
                    printf '  [FAIL] %-52s\n' "场景 brand-intact：报错点名了具体文件"
                    FAIL=$((FAIL + 1)); RESULTS+=("FAIL|brand-intact 报错明细|无明细")
                fi
                ;;
            brand-missing)
                expect 1 "场景 brand-missing：品牌图形缺失应报失败" \
                    bash tools/apply-divergences.sh
                ;;
            unknown-div)
                expect 1 "场景 unknown-div：未实现施加逻辑的偏离应报失败" \
                    bash tools/apply-divergences.sh
                ;;
        esac
        cd "$DV_ORIG" || exit 1
    else
        printf '  [FAIL] %-52s\n' "无法建立偏离夹具（$scenario）"
        FAIL=$((FAIL + 1)); RESULTS+=("FAIL|偏离夹具 $scenario|建立失败")
    fi
    rm -rf "$DV_DIR"
done

# 阶段 0 一致性：applied 非空但上游未导入，必须报错
DV_DIR="$(mktemp -d)"
if bash tools/make-divergence-fixture.sh "$DV_DIR" applied-nonempty >/dev/null 2>&1; then
    rm -f "$DV_DIR/app/build.gradle.kts"     # 回到「上游未导入」状态
    cd "$DV_DIR" || exit 1
    expect 1 "阶段 0 一致性：applied 非空但上游未导入应报错" \
        bash tools/apply-divergences.sh
    cd "$DV_ORIG" || exit 1
else
    printf '  [FAIL] %-52s\n' "无法建立 applied-nonempty 夹具"
    FAIL=$((FAIL + 1)); RESULTS+=("FAIL|applied-nonempty 夹具|建立失败")
fi
rm -rf "$DV_DIR"

section "4c. 偏离锚点预检 check-divergence-anchors.py"

# 正路径：对真实上游检出跑一次（有审计副本时）。
if [ -d ../audit/DSHBox ]; then
    expect 0 "真实上游检出：16 个锚点全部成立" \
        run_tool tools/check-divergence-anchors.py --upstream ../audit/DSHBox
else
    printf '  [SKIP] %-52s 无审计副本\n' "真实上游检出：锚点全部成立"
    SKIP=$((SKIP + 1)); RESULTS+=("SKIP|锚点预检真实上游|无审计副本，未检查")
fi

# 失败路径：在隔离夹具上制造各类漂移。
# 夹具自带 divergences.json（品牌哈希按夹具自身图形登记），
# 因此不依赖真实上游资产，在干净克隆里也能跑。
AF_ORIG="$PWD"
for scenario in ok drift-appid drift-url drift-brand no-website; do
    AF_DIR="$(mktemp -d)"
    if bash tools/make-anchor-fixture.sh "$AF_DIR" "$scenario" >/dev/null 2>&1; then
        case "$scenario" in
            ok)
                expect 0 "场景 ok：锚点全部成立" \
                    run_tool tools/check-divergence-anchors.py \
                        --upstream "$AF_DIR/upstream" \
                        --divergences "$AF_DIR/divergences.json"
                ;;
            drift-appid)
                expect 1 "场景 drift-appid：applicationId 写法变化应报失败" \
                    run_tool tools/check-divergence-anchors.py \
                        --upstream "$AF_DIR/upstream" \
                        --divergences "$AF_DIR/divergences.json"
                ;;
            drift-url)
                expect 1 "场景 drift-url：上游地址消失应报失败" \
                    run_tool tools/check-divergence-anchors.py \
                        --upstream "$AF_DIR/upstream" \
                        --divergences "$AF_DIR/divergences.json"
                ;;
            drift-brand)
                expect 1 "场景 drift-brand：品牌图形哈希变化应报失败" \
                    run_tool tools/check-divergence-anchors.py \
                        --upstream "$AF_DIR/upstream" \
                        --divergences "$AF_DIR/divergences.json"
                ;;
            no-website)
                expect 1 "场景 no-website：website/ 消失应报失败" \
                    run_tool tools/check-divergence-anchors.py \
                        --upstream "$AF_DIR/upstream" \
                        --divergences "$AF_DIR/divergences.json"
                ;;
        esac
    else
        printf '  [FAIL] %-52s\n' "无法建立锚点夹具（$scenario）"
        FAIL=$((FAIL + 1)); RESULTS+=("FAIL|锚点夹具 $scenario|建立失败")
    fi
    rm -rf "$AF_DIR"
done
cd "$AF_ORIG" || exit 1

# 用法错误必须报 2，不能与「锚点不成立（1）」混淆。
expect 2 "上游目录不存在时报用法错误（退出码 2）" \
    run_tool tools/check-divergence-anchors.py --upstream "$SCRATCH/definitely-not-here"

# 本地克隆一个「干净检出」做夹具。
#
# ⚠️ 两个都必须有，缺一个这个夹具就会**时绿时红**：
#
# ① `--no-local`：只要 `.git/objects` 里存在**任何一个符号链接**，
#    走「本地优化」路径的克隆就整条拒绝：
#    `fatal: symlink '11/4fb...' exists, refusing to clone with --local`。
#    本容器里 2380 个松散对象恰好是指向 `/.l2s/.l2s.tmp_obj_*` 的符号链接
#    （文件系统层留下的）。`--no-local` 强制走**真实传输**（upload-pack），
#    它就绕开了那条硬链接路径。
#    ⚠️ `--no-hardlinks` **不够**：那个开关只把「硬链接」换成「拷贝」，
#    而 `.` 这样的本地路径**仍然自动启用 `--local`**，拒绝逻辑照旧触发。
#    我第一版就是写成 `--no-hardlinks`，然后实测仍然红。
#    于是这条夹具**在某些对象状态下成功、在另一些下失败** ——
#    而它红的时候，红的是夹具，不是被查的东西（第 25 条的同族）。
# ② 不能把 stderr 丢进 /dev/null：原来是 `2>/dev/null`，于是上面那句
#    `fatal: …` **一个字都看不到**，只留下一句「无法建立 main 克隆」。
#    **一个失败却不说为什么的夹具，比没有夹具更贵** —— 它会让人去改对的东西。
clone_fixture() {
    local dest="$1" err
    err="$(git clone -q --no-local . "$dest" 2>&1)" && return 0
    printf '  [FAIL] %-52s\n' "本地克隆夹具失败（--no-local）"
    printf '         --- git 说 ---\n'
    printf '%s\n' "$err" | sed 's/^/         /' | head -6
    return 1
}

section "4d. 阶段 1 导入器 import-upstream-base.py"

if [ -d ../audit/DSHBox ]; then
    IMP_UP="$(cd ../audit/DSHBox && pwd)"

    # 正路径 1：在 main 上必须拒绝（强制走分支 + PR）。
    # 必须在专门的 main 克隆里测 —— 套件自身可能就跑在 phase-1 分支上。
    IMP_MAIN="$(mktemp -d)"
    rm -rf "$IMP_MAIN"
    if clone_fixture "$IMP_MAIN" &&
       git -C "$IMP_MAIN" switch -q main 2>/dev/null; then
        expect 1 "在 main 分支上拒绝执行（阶段 1 必须走 PR）" \
            bash -c "cd '$IMP_MAIN' && python3 tools/import-upstream-base.py --upstream '$IMP_UP'"
    else
        printf '  [FAIL] %-52s\n' "无法建立 main 克隆"
        FAIL=$((FAIL + 1)); RESULTS+=("FAIL|导入器 main 拒绝|克隆失败")
    fi
    rm -rf "$IMP_MAIN"

    # 正路径 2：上游提交不符必须拒绝
    IMP_WRONG="$(mktemp -d)"
    rm -rf "$IMP_WRONG"
    cp -a "$IMP_UP" "$IMP_WRONG"
    git -C "$IMP_WRONG" -c user.name=x -c user.email=x@x \
        commit -q --allow-empty -m "夹具：上游又动了一笔"
    expect 1 "上游提交与锁定值不符时拒绝执行" \
        run_tool tools/import-upstream-base.py --upstream "$IMP_WRONG"

    # 完整流程在一次性克隆里跑：dry-run 报告 + apply + 安全网 + 导入后自洽
    #
    # 分支处理要注意：套件既可能在 main 上跑（分支不存在，需要 -c 新建），
    # 也可能正好在 phase-1/import-dshbox-base 上跑（克隆里该分支已存在，
    # `switch -c` 会失败）。两种情况都必须能用 —— 早先只写了 `switch -c`，
    # 于是「在阶段 1 分支上跑套件」会报成「无法建立一次性克隆」，
    # 把一个测试自身的缺陷伪装成环境故障。
    IMP_CLONE="$(mktemp -d)"
    rm -rf "$IMP_CLONE"
    if clone_fixture "$IMP_CLONE" &&
       { git -C "$IMP_CLONE" switch -q phase-1/import-dshbox-base 2>/dev/null ||
         git -C "$IMP_CLONE" switch -q -c phase-1/import-dshbox-base 2>/dev/null; }; then

        # dry-run 必须在克隆里跑 —— run_tool 用的是套件自身的工作目录，
        # 而套件通常跑在 main 上，会被导入器的分支保护直接拒绝。
        expect 0 "分支上 dry-run 通过且与清单预期一致" \
            bash -c "cd '$IMP_CLONE' && python3 tools/import-upstream-base.py --upstream '$IMP_UP'"

        IMP_OUT="$(cd "$IMP_CLONE" && python3 tools/import-upstream-base.py \
                       --upstream "$IMP_UP" --apply 2>&1 || true)"
        if printf '%s' "$IMP_OUT" | grep -q "没有被 .gitignore 吞掉的文件"; then
            printf '  [PASS] %-52s\n' "导入 767 个文件且无文件被 .gitignore 吞掉"
            PASS=$((PASS + 1)); RESULTS+=("PASS|导入安全网|无文件被吞")
        else
            printf '  [FAIL] %-52s\n' "导入安全网"
            FAIL=$((FAIL + 1)); RESULTS+=("FAIL|导入安全网|有文件被 .gitignore 吞掉")
        fi

        # 导入后仓库必须仍然自洽 —— 这是「导入是否真的可用」的关键证据
        expect 0 "导入后引用检查仍通过" \
            bash -c "cd '$IMP_CLONE' && python3 tools/check-references.py"
        expect 0 "导入后契约检查仍通过" \
            bash -c "cd '$IMP_CLONE' && python3 tools/check-upstreams.py"
        expect 0 "导入后许可原件哈希仍一致" \
            bash -c "cd '$IMP_CLONE' && python3 tools/check-license-hashes.py"

        # 偏离重放此时应报「导入后天然仍成立的项 = 导入排除项 + 断言不依赖上游文件的项」，
        # 其余全部报失败。设计如此，不是缺陷。
        #
        # 断言**从真实数据推导**，既不硬编码数量，也不假设「除排除项外全都该失败」：
        #  ① 导入排除项（import-exclusions.json 的 excluded_paths[].divergence）导入后天然已生效；
        #  ② target_files 全都不在「本次导入实际改动的文件」里的偏离，其断言查的是本项目
        #     自有文件（例如 DIV-0015 查 tools/ 下的脚本），导入没碰它们，因此仍然成立。
        # 其余的都依赖上游文件，导入后必须报失败 —— 这正是本夹具要守的东西。
        #
        # ⚠️ 这里原先写的是「失败数 = 登记总数 − 排除数」。DIV-0015 加进来之后它立刻失效：
        # DIV-0015 的断言查的是 tools/ 下本项目自有文件，导入后仍然通过，于是数字对不上。
        # 那次失败**本机是红的、CI 里却看不见**（CI 没有 ../audit/DSHBox，整节只记一项跳过），
        # 于是一条红着的断言躺了很久没人发现 —— 与「断言从未在 CI 里跑过」是同一个病。
        # 改成集合推导后，新增偏离自动跟上，也不会因为「谁查自有文件」而误报。
        IMP_DIV_VERDICT="$(cd "$IMP_CLONE" && python3 - <<'PYDIV'
import json, re, subprocess

out = subprocess.run(["bash", "tools/apply-divergences.sh", "--check"],
                     capture_output=True, text=True).stdout
d = json.load(open("governance/divergences.json", encoding="utf-8"))
x = json.load(open("governance/import-exclusions.json", encoding="utf-8"))
excl = {e.get("divergence") for e in x.get("excluded_paths", [])}

changed = {l[3:] for l in subprocess.run(["git", "status", "--porcelain"],
                                         capture_output=True, text=True).stdout.splitlines()
           if len(l) > 3}
if not changed:
    print("夹具是空的：导入没有改动任何文件，这条断言没有意义")
    raise SystemExit

expect_applied = [e["id"] for e in d["planned"]
                  if e["id"] in excl
                  or not any(any(c == t or c.startswith(t) for c in changed)
                             for t in (e.get("target_files") or []))]
actual_applied = re.findall(r"\[已生效\]\s+(DIV-\d+)", out)
actual_failed = re.findall(r"\[失败\s*\]\s+(DIV-\d+)", out)

if len(actual_applied) + len(actual_failed) != len(d["planned"]):
    print("重放没有对全部 %d 项给出结论（已生效 %d、失败 %d）"
          % (len(d["planned"]), len(actual_applied), len(actual_failed)))
elif sorted(actual_applied) != sorted(expect_applied):
    print("已生效集合不符：期望 %s，实际 %s"
          % (",".join(sorted(expect_applied)), ",".join(sorted(actual_applied))))
else:
    print("OK 已生效 %s；其余 %d 项如预期报失败"
          % (",".join(sorted(actual_applied)), len(actual_failed)))
PYDIV
)"
        case "$IMP_DIV_VERDICT" in
            OK*)
                printf '  [PASS] %-52s\n' "导入后偏离重放：$IMP_DIV_VERDICT"
                PASS=$((PASS + 1)); RESULTS+=("PASS|导入后偏离重放|$IMP_DIV_VERDICT")
                ;;
            *)
                printf '  [FAIL] %-52s\n' "导入后偏离重放：$IMP_DIV_VERDICT"
                FAIL=$((FAIL + 1)); RESULTS+=("FAIL|导入后偏离重放|$IMP_DIV_VERDICT")
                ;;
        esac
    else
        printf '  [FAIL] %-52s\n' "无法建立一次性克隆"
        FAIL=$((FAIL + 1)); RESULTS+=("FAIL|导入器一次性克隆|建立失败")
    fi
    rm -rf "$IMP_CLONE" "$IMP_WRONG"
else
    printf '  [SKIP] %-52s 无审计副本\n' "阶段 1 导入器"
    SKIP=$((SKIP + 1)); RESULTS+=("SKIP|阶段 1 导入器|无审计副本，未检查")
fi

section "4e. 继承 lint 基线的两种失败模式（隔离测试）"

# 这个门禁的价值全在于「它不是橡皮章」。所以不能只测它放行，
# 必须证明它**会拦**：新引入的告警要拦，基线自身过期也要拦。
# 没有这一节，基线就退化成一张永久忽略列表。
if command -v shellcheck >/dev/null 2>&1 && [ -f tools/check-lint-baseline.py ]; then
    LB_BAK="$(mktemp)"
    cp governance/inherited-lint-baseline.json "$LB_BAK"

    # 失败模式 1：新引入一条告警 —— 必须被拦
    cat > tools/_tmp_lb_violation.sh <<'EOF'
#!/usr/bin/env bash
f() {
    local out=$(echo hi)
    echo "$out"
}
f
EOF
    expect 1 "基线门禁：拦下新引入的告警" \
        run_tool tools/check-lint-baseline.py
    rm -f tools/_tmp_lb_violation.sh

    # 失败模式 2：基线里登记一条不存在的条目 —— 必须被拦（防基线失真）
    python3 - <<'PYEOF'
import json
p = 'governance/inherited-lint-baseline.json'
d = json.load(open(p, encoding='utf-8'))
d['shellcheck']['findings'].append({
    "file": "tools/build_apk.sh", "line": 99999, "column": 1, "code": "SC9999",
    "level": "warning", "summary": "套件临时注入的过期条目",
    "inherited_from": "test", "functional_impact": "无"})
json.dump(d, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
PYEOF
    expect 1 "基线门禁：拦下过期（不存在的）基线条目" \
        run_tool tools/check-lint-baseline.py

    # 恢复原基线，并确认恢复后确实放行
    cp "$LB_BAK" governance/inherited-lint-baseline.json
    rm -f "$LB_BAK"
    expect 0 "基线门禁：恢复后放行真实状态" \
        run_tool tools/check-lint-baseline.py
else
    printf '  [SKIP] %-52s 缺 shellcheck 或检查器\n' "继承 lint 基线"
    SKIP=$((SKIP + 3))
    RESULTS+=("SKIP|继承 lint 基线|缺 shellcheck 或检查器，未检查")
fi

section "4f. 导入保真度（逐 blob 对照上游）"

# 「原样导入上游基线」是本阶段的核心承诺，必须可验证，不能只信导入器自己的话。
# 比对的是 git 索引里的 blob 哈希（不是工作区内容）—— 那才是别人 clone 后拿到的。
# 已知差异必须全部能对上 divergences.json / import-exclusions.json 的登记。
# 首次运行就是靠这一项发现 gradlew.bat 被 .gitattributes 改了行尾。
if [ -d "$ROOT/../audit/DSHBox/.git" ]; then
    expect 0 "导入文件与上游逐字节一致（差异均已登记）" \
        run_tool tools/check-import-fidelity.py
else
    printf '  [SKIP] %-52s 无上游审计副本\n' "导入保真度"
    SKIP=$((SKIP + 1))
    RESULTS+=("SKIP|导入保真度|无上游审计副本，未检查")
fi

# 「没有上游副本」必须优雅跳过，而不是崩溃。
# 这一条是被 CI 打脸后补的：工具里曾写死一个本机绝对路径，本地有那个目录
# 所以一切正常，CI 上没有 —— 于是 PermissionError 让脚本直接退出 1。
# 我测过这个工具，但只测了「能找到上游」这一条路径。
# 现在把三条降级路径都测：找不到、无权访问、不是 git 检出。
expect 0 "无上游副本时优雅跳过（不崩溃）" \
    env DSHFUSION_UPSTREAM_DSHBOX=/root/nonexistent/DSHBox \
        python3 tools/check-import-fidelity.py
FID_TMP="$(mktemp -d)"
tar -c --exclude=.git -C "$ROOT" . 2>/dev/null | tar -x -C "$FID_TMP" 2>/dev/null || true
if [ -f "$FID_TMP/tools/check-import-fidelity.py" ]; then
    expect 0 "非 git 检出时优雅跳过（不崩溃）" \
        bash -c "cd '$FID_TMP' && python3 tools/check-import-fidelity.py"
fi
rm -rf "$FID_TMP"

section "5. 静态检查"

expect 0 "所有 shell 脚本语法正确（bash -n）" \
    bash -c 'for f in tools/*.sh; do bash -n "$f" || exit 1; done'
expect 0 "所有 Python 工具可编译" \
    python3 -m compileall -q tools
expect 0 "所有 JSON 可解析" \
    bash -c 'for f in $(find . -name "*.json" -not -path "./.git/*"); do python3 -c "import json,sys; json.load(open(sys.argv[1],encoding=\"utf-8\"))" "$f" || exit 1; done'
expect 0 "受保护许可原件哈希与登记一致" \
    run_tool tools/check-license-hashes.py

# 隐私审计只在 android.yml 里跑，本地不查就会重复踩「本地绿、CI 红」的坑。
# 2026-10-13 实测：它在 DSHFusion 里报出 34 处，其中 31 处是我们自己文件里
# 写死的 /tmp 路径（真实工程缺陷，已改用 mktemp），
# 1 处是保留域 .invalid 被当成真实邮箱，2 处来自哈希锁定的上游许可冻结副本
# —— 那个目录我们无权修改，属于两个门禁互相矛盾，已在审计侧跳过（DIV-0007）。
expect 0 "隐私审计（发布前个人数据泄露）" \
    run_tool tools/privacy_audit.py

# 应用标识一致性。这一条是 CI 打脸后补的：namespace 改错会让 36 个
# `import com.dshbox.app.R` 编译失败，而且 manifest 的相对类名会在**运行时**
# 崩 —— 后者编译期不一定报错。本环境跑不了 gradle，但这类错误纯静态可判。
expect 0 "应用标识一致（namespace 对得上 R 引用与 manifest 相对类名）" \
    run_tool tools/check-android-identity.py
# 光有检查不够：得证明它真的会失败，否则和没检查一样。
IDENT_BAK="$(mktemp)"
cp app/build.gradle.kts "$IDENT_BAK"
sed -i 's/namespace = "com.dshbox.app"/namespace = "com.dshfusion.app"/' app/build.gradle.kts
expect 1 "故障注入：namespace 被改错时必须报错" \
    run_tool tools/check-android-identity.py
cp "$IDENT_BAK" app/build.gradle.kts
rm -f "$IDENT_BAK"
expect 0 "恢复 namespace 后重新通过" \
    run_tool tools/check-android-identity.py

section "5b. 工作流验证（真实解析器 / actionlint）"

# PyYAML 与 actionlint 都是可选依赖。缺失时报告 SKIP，而不是假装通过。
if python3 -c "import yaml" 2>/dev/null; then
    expect 0 "工作流 YAML 可被真实解析器解析且结构完整" \
        run_tool tools/check-workflows.py

    # 内联 Python 的**语法**。YAML 解析器不看它，actionlint 也不看它的宿主语言，
    # 于是那段几十行的脚本只在 CI 上跑 —— 而跑到它要十几分钟。
    # 2.5 的计数步骤因此把 `):` 的错位带进了 CI，第 13 步才红（第 36 条）。
    expect 0 "工作流内联 Python 语法可被编译器解析" \
        run_tool tools/check-workflow-python.py

    # ⚠️ 上面那条只证明「现在是对的」。下面证明它**会红** ——
    # 否则「加了一条检查」和「这条检查能失败」是两件事（第 34 条）。
    # 注入形态就取我真实犯过的那个：把元组的 `):` 挪到第一个条目之后。
    WFP_BAK="$(mktemp)"
    cp "$ROOT/.github/workflows/android.yml" "$WFP_BAK"
    # ⚠️ 注入必须**自证它真的落在了它以为的地方**（第 31 条）。
    # 这里用文件哈希：改完哈希没变，说明锚点没匹配上、文件根本没被改 ——
    # 那次注入什么都没测到，而下面的检查会照常变绿，于是这条注入**静默失效**。
    # 实测发生过：我往 android.yml 的元组里加了一条之后，这一段注入的锚点不再匹配，
    # `assert` 抛错、文件原样不动，而套件报的是「检查没有变红 —— 它对这个方向是瞎的」。
    # 那句报错是**对的结论、错的原因**：瞎的不是检查，是这条注入。
    WFP_BEFORE="$(sha256sum "$ROOT/.github/workflows/android.yml" | cut -d' ' -f1)"
    python3 - <<'PYI'
import pathlib, re, sys
p = pathlib.Path(".github/workflows/android.yml")
t = p.read_text(encoding="utf-8")
# 不再钉死具体那一行（它会随工作流演进而漂），改成按**结构**定位：
# 把元组的终止符 `):` 插到列表的第一项之后。
# 这与我真实犯过的错同形 —— 终止符提前出现。
# 一个完整的元组 = 两行：`("类名", 条数,` + `"理由"),`。
pattern = re.compile(
    r'(\(\s*"com\.[^"]+",\s*\d+,\n\s+"[^"]*"\),\n)', re.M)
match = pattern.search(t)
if not match:
    print("INJECTION_ANCHOR_NOT_FOUND", file=sys.stderr)
    sys.exit(3)
t = t[: match.end(1)] + "          ):\n" + t[match.end(1):]
p.write_text(t, encoding="utf-8")
PYI
    WFP_INJ_RC=$?
    WFP_AFTER="$(sha256sum "$ROOT/.github/workflows/android.yml" | cut -d' ' -f1)"
    if [ "$WFP_INJ_RC" != 0 ] || [ "$WFP_BEFORE" = "$WFP_AFTER" ]; then
        fail "内联 Python 的注入**没有生效**（锚点没匹配上，文件一个字节都没变）—— 这条注入需要修，不是检查需要修"
        CR_BAD=1
    elif python3 tools/check-workflow-python.py >/dev/null 2>&1; then
        fail "把元组的 ): 挪到条目中间之后，内联 Python 检查没有变红 —— 它对这个方向是瞎的"
        CR_BAD=1
    else
        ok "内联 Python 检查能抓住元组提前闭合（我真实犯过的那个错）"
    fi
    cp "$WFP_BAK" "$ROOT/.github/workflows/android.yml"
    rm -f "$WFP_BAK"
    if python3 tools/check-workflow-python.py >/dev/null 2>&1; then
        ok "复原后内联 Python 检查重新通过"
    else
        fail "复原后内联 Python 检查仍未通过 —— 注入没被还原干净"
        CR_BAD=1
    fi
else
    printf '  [SKIP] %-52s 未安装 PyYAML\n' "工作流 YAML 解析"
    SKIP=$((SKIP + 1))
    RESULTS+=("SKIP|工作流 YAML 解析|未安装 PyYAML，未检查")
fi

# 允许用环境变量指定；否则在 PATH 里找。
# 原先候选列表里写死了一个本机安装位置，既进了发布内容（隐私审计会报
# 「本机痕迹」），对别人也毫无意义。现在只认 PATH 与标准安装目录。
ACTIONLINT="${ACTIONLINT:-}"
if [ -z "$ACTIONLINT" ]; then
    for candidate in actionlint /usr/local/bin/actionlint; do
        if command -v "$candidate" >/dev/null 2>&1; then ACTIONLINT="$candidate"; break; fi
    done
fi
if [ -n "$ACTIONLINT" ]; then
    expect 0 "actionlint 语义检查（含 shellcheck）" \
        "$ACTIONLINT" -no-color .github/workflows/contracts.yml .github/workflows/upstream-watch.yml
else
    printf '  [SKIP] %-52s 未找到 actionlint\n' "actionlint 语义检查"
    SKIP=$((SKIP + 1))
    RESULTS+=("SKIP|actionlint 语义检查|未安装 actionlint，未检查")
fi

if command -v shellcheck >/dev/null 2>&1; then
    # 阶段 1 起 tools/ 里有上游原样导入的脚本，带着 9 条上游既有告警。
    # 门禁因此改成「对照继承基线」：只拦新引入的，同时检测基线是否过期。
    # 基线本身的两种失败模式在 4e 节有隔离测试。
    expect 0 "tools/*.sh 无新增 shellcheck 告警（对照继承基线）" \
        run_tool tools/check-lint-baseline.py
else
    printf '  [SKIP] %-52s 未找到 shellcheck\n' "tools/*.sh shellcheck"
    SKIP=$((SKIP + 1))
    RESULTS+=("SKIP|tools/*.sh shellcheck|未安装 shellcheck，未检查")
fi

section "5c. DSH 层构建脚本 build-dsh-layer.sh"

# 这个脚本要真的能拦住错误，否则「层可复现」只是句口号。
# 完整的 npm 安装要 3 分钟、500 MB，不适合放进每次提交都跑的套件，
# 所以这里只测**参数校验与失败路径**，装包本身由人工按需运行。

expect 0 "脚本存在且语法正确" \
    bash -n tools/build-dsh-layer.sh

# 版本必须来自 governance/upstreams.json，而不是脚本里写死的默认值。
# 上游 install_dsh.sh 默认 0.1.0-rc.6、实际适配 0.2.0-rc.2 —— 那个漂移（R3）
# 就是写死默认值造成的。这里验证脚本报出的版本确实等于 upstreams.json 里的值。
EXPECTED_VER="$(python3 -c "
import json
d = json.load(open('governance/upstreams.json', encoding='utf-8'))
print(next(e['tracked_version'] for e in d['transitive_upstreams']
           if e.get('id') == 'deepseek-harness'))
")"
[ -n "$EXPECTED_VER" ] || fail "upstreams.json 里读不到 deepseek-harness 的版本"

# 给一个能通过校验的假 node，但不给 npm —— 脚本会先报出版本再死在 npm 检查上。
FAKEBIN="$(mktemp -d)"
printf '#!/bin/sh\necho v24.19.0\n' > "$FAKEBIN/node"
chmod +x "$FAKEBIN/node"
VER_OUT="$(env PATH="$FAKEBIN:/usr/bin:/bin" bash tools/build-dsh-layer.sh "$SCRATCH/layer" 2>&1 || true)"
case "$VER_OUT" in
    *"$EXPECTED_VER"*) ok "版本取自 upstreams.json（$EXPECTED_VER），不是写死的默认值" ;;
    *) fail "脚本报出的版本与 upstreams.json 不一致；输出：$VER_OUT" ;;
esac
case "$VER_OUT" in
    *"找不到 npm"*) ok "缺 npm 时明确失败" ;;
    *) fail "缺 npm 时未给出明确错误；输出：$VER_OUT" ;;
esac

# 上面那条断言有个盲区：如果脚本里写死了一个恰好等于当前上游的默认版本，
# 它照样会通过。所以再加一条 —— 脚本正文里不得出现 DSH 版本字面量。
# 上游 install_dsh.sh 的 0.1.0-rc.6 漂移就是这么来的。
if grep -qE '"[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?"' tools/build-dsh-layer.sh; then
    fail "build-dsh-layer.sh 里出现版本字面量，应只从 upstreams.json 读：$(grep -nE '"[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?"' tools/build-dsh-layer.sh | head -3)"
else
    ok "脚本正文不含 DSH 版本字面量（版本只来自 upstreams.json）"
fi

# 失败路径：没有 node
expect 2 "失败路径：找不到 node 时退出码 2" \
    env PATH=/usr/bin:/bin bash tools/build-dsh-layer.sh "$SCRATCH/layer2"

# 失败路径：node 报告的版本不是数字。
# `[ "$X" -lt 20 ]` 在 X 非整数时会报错并**返回假**，从而把低版本当成通过 ——
# 曾经就是这个缺陷让报错位置跑到冒烟测试去，离真正原因很远。
printf '#!/bin/sh\necho garbage\n' > "$FAKEBIN/node"
chmod +x "$FAKEBIN/node"
expect 2 "失败路径：node 版本无法解析时退出码 2（不能当通过）" \
    env PATH="$FAKEBIN:/usr/bin:/bin" bash tools/build-dsh-layer.sh "$SCRATCH/layer3"
rm -rf "$FAKEBIN"

# 失败路径：upstreams.json 里没有 deepseek-harness 条目
UPSTREAMS_BAK="$(mktemp)"
cp governance/upstreams.json "$UPSTREAMS_BAK"
python3 - <<'PYEOF'
import json
p = "governance/upstreams.json"
d = json.load(open(p, encoding="utf-8"))
d["transitive_upstreams"] = [e for e in d["transitive_upstreams"]
                             if e.get("id") != "deepseek-harness"]
json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PYEOF
expect 2 "失败路径：upstreams.json 读不到版本时退出码 2" \
    env PATH=/usr/local/bin:/usr/bin:/bin DSH_VERSION= bash tools/build-dsh-layer.sh "$SCRATCH/layer4"
cp "$UPSTREAMS_BAK" governance/upstreams.json
rm -f "$UPSTREAMS_BAK"
expect 0 "恢复 upstreams.json 后版本可读" \
    bash -c 'python3 -c "
import json
d = json.load(open(\"governance/upstreams.json\", encoding=\"utf-8\"))
assert any(e.get(\"id\") == \"deepseek-harness\" for e in d[\"transitive_upstreams\"])
"'

section "5c-2. 运行环境资产构建脚本 build-runtime-assets.sh"

expect 0 "语法检查" bash -n tools/build-runtime-assets.sh

# 核心断言：**缺 zstd 必须失败退出，不能降级到 gzip**。
#
# 这不是风格偏好。AGP 会把 `.gz` 资源解压并去掉扩展名，于是
#     runtime/android-side.tar.gz  →  assets/runtime/android-side.tar
# 而应用侧要求名字以 `android-side.tar.` 开头（带那个点），匹配不上 ——
# 层进了 APK，但应用永远看不见它，等于没建。
# 一个必然被剥坏的产物，比一个明确的报错危险得多。
#
# 构造一个「有全套工具、独缺 zstd」的 PATH 来跑真实失败路径。
#
# ⚠️ 这一条此前**在 CI 上从来没跑过**。CI 的 ubuntu-latest 自带 zstd，
# 于是 `command -v zstd` 成立，一直走 skip；它只在没有 zstd 的机器上被验证过。
# 而 CI 恰恰是唯一会真的跑构建的地方 —— 一个只在「本机恰好缺某工具」时才执行的
# 断言，等于没有覆盖。
#
# 做法：把 /usr/local/bin、/usr/bin、/bin 里**除 zstd 之外**的全部命令软链到
# 一个临时目录，PATH 只指向它。「没有 zstd」因此是真的，不是模拟出来的。
# 注意不能只把假 zstd 放在 PATH 最前面 —— 后面的 /usr/bin 还是会找到真的那个。
NOSZSTD="$SCRATCH/nozstd-bin"
mkdir -p "$NOSZSTD"
for d in /usr/local/bin /usr/bin /bin; do
    [ -d "$d" ] || continue
    for f in "$d"/*; do
        b="${f##*/}"
        [ "$b" = "zstd" ] && continue
        [ -e "$NOSZSTD/$b" ] || ln -s "$f" "$NOSZSTD/$b" 2>/dev/null || true
    done
done

if env PATH="$NOSZSTD" /bin/bash -c 'command -v zstd' >/dev/null 2>&1; then
    skip "缺 zstd 的失败路径（受限 PATH 里仍能找到 zstd，构造失败）"
else
    ASIDE_OUT="$(env PATH="$NOSZSTD" /bin/bash tools/build-runtime-assets.sh "$SCRATCH/nozstd" 2>&1)" || ASIDE_RC=$?
    ASIDE_RC="${ASIDE_RC:-0}"
    if [ "$ASIDE_RC" -ne 2 ]; then
        fail "缺 zstd 时应退出 2，实际 $ASIDE_RC"
    else
        ok "缺 zstd 时退出 2（拒绝产出必然被 AGP 剥坏的 gzip 层）"
    fi
    # 断言失败消息**解释了原因**，而不是只说「找不到 zstd」。
    # 差别是实质性的：只说「找不到 zstd」会让人以为是可选依赖、装不装都行；
    # 说清「gzip 产物必然被 AGP 剥坏」才会让人去装它而不是绕开。
    case "$ASIDE_OUT" in
        *"没有别的可行格式"*)
            ok "失败信息解释了为什么 gzip 不可用（不是只说「找不到 zstd」）" ;;
        *) fail "失败信息没有解释 gzip 为何不可用，用户会以为是可选依赖" ;;
    esac
fi

# 脚本正文不得再出现 gzip 打包路径。这条是防回归：
# 换回 `tar -czf` 会让上面那条断言之外的所有检查都通过，而产物是坏的。
if grep -qE 'tar .*-czf|tar .*-zcf' tools/build-runtime-assets.sh; then
    fail "build-runtime-assets.sh 里仍有 gzip 打包路径（-czf）"
else
    ok "build-runtime-assets.sh 不含 gzip 打包路径"
fi
if grep -qE 'tar .*-czf|tar .*-zcf' tools/build-dsh-layer.sh; then
    fail "build-dsh-layer.sh 里仍有 gzip 打包路径（-czf）"
else
    ok "build-dsh-layer.sh 不含 gzip 打包路径"
fi

# 两层产物的**文件名赋值**必须是 `.tar.zst` —— 后缀决定应用侧能不能找到它。
#
# 只看赋值行（`ASIDE_TAR=` / `TARBALL=`），不看整个文件：脚本的注释里
# **必须**能提到 `.tar.gz`，那正是「为什么不能用 gzip」的解释所在。
# 第一版这里 grep 了整个文件，于是把解释性注释也判成了违规 ——
# 一个过宽的检查会逼着人删掉注释，而不是修掉问题。
if grep -nE '^[[:space:]]*(ASIDE_TAR|TARBALL|OUT_FILE)=.*\.tar\.gz' \
        tools/build-runtime-assets.sh tools/build-dsh-layer.sh; then
    fail "上面这些赋值行仍产出 .tar.gz"
else
    ok "两个脚本的层文件赋值都只产出 .tar.zst"
fi

# 收尾断言必须钉住**精确文件名**。用 glob（`android-side.tar.*`）会让
# `.sha256` 侧车也满足它 —— check-apk-identity.py 的断言 6 第一版就是这么被骗的。
if grep -q 'runtime/android-side\.tar\.zst' tools/build-runtime-assets.sh; then
    ok "收尾断言使用精确文件名（不会被 .sha256 侧车满足）"
else
    fail "收尾断言没有钉住精确文件名"
fi

section "5d. APK 产物身份核验 check-apk-identity.py"

# 这个工具的价值全在断言上，所以不能只测「文件不存在时退出 2」——
# 那测的是 argparse。用一个手写的合成 APK 逐个场景验证断言真的会拦。
expect 0 "--help 可用" \
    run_tool tools/check-apk-identity.py --help
expect 2 "失败路径：APK 不存在" \
    run_tool tools/check-apk-identity.py "$SCRATCH/definitely-not-an.apk"
printf 'not a zip' > "$SCRATCH/notzip.apk"
expect 2 "失败路径：不是 zip" \
    run_tool tools/check-apk-identity.py "$SCRATCH/notzip.apk"

# 七个场景。第一个是「应该通过」，中间五个是「断言应该拦住」，
# 最后一个是结构缺失（没有 manifest）。
expect 0 "合成 APK：身份正确时应通过" \
    bash -c "python3 tools/make-apk-identity-fixture.py '$SCRATCH/good.apk' good >/dev/null && \
             python3 tools/check-apk-identity.py '$SCRATCH/good.apk'"

for scen in upstream-id upstream-ver wrong-ns wrong-label bad-authority \
            no-layers sidecar-only gz-stripped x64-layer arm64-layer; do
    python3 tools/make-apk-identity-fixture.py "$SCRATCH/$scen.apk" "$scen" >/dev/null
done

# 逐个断言「拦得住」，并核对报出的原因确实是那一条（不是碰巧失败）
check_scenario() {
    local scen="$1" expect_text="$2"
    local out rc
    out="$(python3 tools/check-apk-identity.py "$SCRATCH/$scen.apk" 2>&1)" || rc=$?
    rc="${rc:-0}"
    if [ "$rc" -ne 1 ]; then
        fail "场景 $scen 应退出 1，实际 $rc"
        return
    fi
    case "$out" in
        *"$expect_text"*) ok "场景 $scen 被拦住，原因是预期的：$expect_text" ;;
        *) fail "场景 $scen 退出了 1，但原因不是预期的「$expect_text」" ;;
    esac
}

check_scenario upstream-id   "applicationId 仍是上游的"
check_scenario upstream-ver  "版本号仍是上游的"
check_scenario wrong-ns      "期望在 com.dshbox.app 下"
check_scenario wrong-label   "期望 'DSHFusion'"
# 这一条是真机装不上之后补的：authority 写死成上游的值会让安装失败 -13。
# 它必须能独立拦住，而不是靠别的断言顺带发现。
check_scenario bad-authority "不是从 applicationId 派生的"
# 缺运行环境层：这条以前只是「备注」，所以它如实备注了「不含任何层」
# 然后 CI 照样全绿 —— 缺层的 APK 一路走到了真机。现在它是硬断言。
# no-layers 场景的身份全部正确，只有层缺失，确保它只因为这一条失败。
check_scenario no-layers     "缺可用的运行环境层"

# 下面两条是断言 6 自己出过的错，各对应一种「看着有层、实际不能用」的形态。
# 它们必须独立断言：光有 no-layers 只能证明「完全没层」会被拦住，
# 证明不了「层在但用不了」也会被拦住 —— 而后者才是真正骗过 CI 的那种。
#
# sidecar-only：目录里只有 .sha256 侧车。第一版断言 6 写的是
# `startswith("android-side.tar.")`，`android-side.tar.gz.sha256` 满足这个前缀，
# 于是 CI 全绿而 APK 里根本没有层本体。
check_scenario sidecar-only  "但没有应用侧会查找的层本体"
# gz-stripped：模拟 AGP 的真实行为 —— `.tar.gz` 被解压并去掉扩展名，
# 在 APK 里变成 `android-side.tar`（裸 tar）。名字少了那个点，应用匹配不上。
# 这是 run 38513950774 产物的真实形态。
check_scenario gz-stripped   "但没有应用侧会查找的层本体"

# ---- 断言 7：层的目标架构（DIV-0015）----
#
# 这一条是 2026-10-14 真机失败的形态：身份全对、层名与容器格式全对、
# 上面所有断言全部通过、**node 也真的起来了**，然后 DSH 在 host preparation
# 阶段致命退出：
#
#     dsh: host preparation failed: No usable native binding found for
#          node-addon-require-builtin-linux-arm64-gnu (auto)
#
# 层是 CI 在 x86_64 上建的，npm 按宿主平台装了整套 `-linux-x64` 可选依赖。
#
# 两个场景**必须成对**：只测 x64-layer 只能证明「它失败了」，
# 证明不了失败的原因是架构 —— 它也可能碰巧被别的断言拦下。
# arm64-layer 与 x64-layer 只差层里那 7 个包，它必须通过。
check_scenario x64-layer     "dsh 层不是 arm64 架构的"

expect 0 "合成 APK：层里是 arm64 的包时应通过" \
    run_tool tools/check-apk-identity.py "$SCRATCH/arm64-layer.apk"

# 反向注入：断言 7 必须**活着**。
#
# 上面两条只证明「x64 的层会被拦住」，证明不了拦住它的**是**断言 7 ——
# 也可能是别的断言顺手拦下的。所以把断言 7 的代码路径掐掉
# （`if dsh:` → `if False:`，只动第一处），同一个夹具必须**变回通过**。
# 若掐掉之后仍然失败，说明这两条测的根本不是架构。
#
# 这个做法本身就是本项目的教训之一：5d/5e 两节曾经有 11 项断言因为
# `ok()` 函数不存在而静默变成空操作，套件照样报「全部通过」。
# 所以「断言在场」和「断言在跑」必须分开证明。
cp tools/check-apk-identity.py "$SCRATCH/checker-no-assert7.py"
sed -i '0,/^    if dsh:$/s//    if False:/' "$SCRATCH/checker-no-assert7.py"
if grep -q '^    if False:$' "$SCRATCH/checker-no-assert7.py"; then
    expect 0 "注入验证：掐掉断言 7 后 x64 夹具变回通过（证明拦住它的就是断言 7）" \
        run_tool "$SCRATCH/checker-no-assert7.py" "$SCRATCH/x64-layer.apk"
else
    fail "注入失败：没能掐掉断言 7，本节的反向验证没有生效"
fi

# 源码层的同一条检查（构建前就报，不用等 APK）
python3 tools/check-android-identity.py >/dev/null 2>&1 \
    && ok "源码 authority 全部派生自 applicationId" \
    || fail "源码里仍有写死的 authority"

# 注入：把 manifest 的 authority 改回硬编码，源码检查必须失败
MF_BAK="$SCRATCH/manifest.bak"
cp app/src/main/AndroidManifest.xml "$MF_BAK"
sed -i 's|android:authorities="${applicationId}.fileprovider"|android:authorities="com.dshbox.app.fileprovider"|' \
    app/src/main/AndroidManifest.xml
if python3 tools/check-android-identity.py >/dev/null 2>&1; then
    fail "注入：manifest authority 改回硬编码，源码检查没拦住"
else
    ok "注入：manifest authority 改回硬编码 → 源码检查拦下"
fi
cp "$MF_BAK" app/src/main/AndroidManifest.xml
python3 tools/check-android-identity.py >/dev/null 2>&1 \
    && ok "注入后恢复：源码检查回到通过" \
    || fail "恢复后源码检查仍失败"

expect 2 "失败路径：包里没有 AndroidManifest.xml" \
    bash -c "python3 tools/make-apk-identity-fixture.py '$SCRATCH/no-manifest.apk' no-manifest >/dev/null && \
             python3 tools/check-apk-identity.py '$SCRATCH/no-manifest.apk'"

section "5e. 许可状态：根 LICENSE 是说明而非 GPL 全文（DIV-0009）"

# 这一节的重点不是「根目录不再是 GPL」，而是「GPL 没丢」。
# 只检查前者等于允许许可证在换位置的过程中凭空消失。
if grep -q 'GNU GENERAL PUBLIC LICENSE' LICENSE 2>/dev/null; then
    fail "根 LICENSE 仍是 GPL-3.0 全文（DIV-0009 未生效）"
else
    ok "根 LICENSE 不是 GPL-3.0 全文"
fi
if grep -q '许可状态：私有自用' LICENSE 2>/dev/null; then
    ok "根 LICENSE 含 DSHFusion 许可状态标记"
else
    fail "根 LICENSE 缺少 DSHFusion 许可状态标记"
fi
expect 0 "受保护的上游 GPL 副本存在且哈希正确（换位置不等于删掉）" \
    run_tool tools/check-license-hashes.py
expect 0 "许可原件核验覆盖 5 个文件" \
    bash -c "python3 tools/check-license-hashes.py 2>&1 | grep -q '5 个文件'"

# 故障注入：把受保护的 GPL 副本删掉，DIV-0009 必须失败。
# 不注入就只是在测「happy path 能过」，测不到安全网。
GPL_COPY="governance/upstream-licenses/DSHBox/LICENSE"
GPL_BAK="$SCRATCH/GPL.bak"
cp "$GPL_COPY" "$GPL_BAK"
rm "$GPL_COPY"
out="$(bash tools/apply-divergences.sh --check 2>&1)" || true
case "$out" in
    *"DIV-0009"*"不存在"*) ok "注入：删掉 GPL 副本 → DIV-0009 失败并点名文件" ;;
    *) fail "注入：删掉 GPL 副本后 DIV-0009 没有正确失败" ;;
esac
cp "$GPL_BAK" "$GPL_COPY"

# 故障注入：改副本一个字节 → 哈希必须报错
printf 'x' >> "$GPL_COPY"
out="$(bash tools/apply-divergences.sh --check 2>&1)" || true
case "$out" in
    *"DIV-0009"*"哈希不符"*) ok "注入：改动 GPL 副本 → DIV-0009 报哈希不符" ;;
    *) fail "注入：改动 GPL 副本后 DIV-0009 没有报哈希不符" ;;
esac
cp "$GPL_BAK" "$GPL_COPY"

# 故障注入：把 GPL 全文塞回根目录（模拟上游合并把它带回来）
LIC_BAK="$SCRATCH/LICENSE.bak"
cp LICENSE "$LIC_BAK"
cp "$GPL_BAK" LICENSE
out="$(bash tools/apply-divergences.sh --check 2>&1)" || true
case "$out" in
    *"DIV-0009"*"GPL-3.0 全文"*) ok "注入：根 LICENSE 变回 GPL → DIV-0009 拦下" ;;
    *) fail "注入：根 LICENSE 变回 GPL 后 DIV-0009 没有拦下" ;;
esac
# 非 --check 模式下必须能自动改回
bash tools/apply-divergences.sh >/dev/null 2>&1 || true
if grep -q '许可状态：私有自用' LICENSE && ! grep -q 'GNU GENERAL PUBLIC LICENSE' LICENSE; then
    ok "注入后重放：根 LICENSE 被自动改回说明"
else
    fail "重放后根 LICENSE 没有自动改回说明"
fi
cp "$LIC_BAK" LICENSE
cp "$GPL_BAK" "$GPL_COPY"

section "5f. install_dsh.sh 不再有默认 DSH 版本（DIV-0010）"

# R3 原先写的是「已从结构上消除」，那是过头话：新脚本只是新增了一条正确路径，
# 没有碰 install_dsh.sh 里那个 0.1.0-rc.6 默认值。这一节测的就是那个默认值没了。
INSTALL_DSH="runtime-bundle/scripts/install_dsh.sh"
# 只看正文，去掉注释行。
# 这个区分是必需的：脚本的注释里**故意**引用了原来那个默认值
# （「这里原本写的是 DSH_VERSION="${DSH_VERSION:-0.1.0-rc.6}"」），
# 不过滤注释就会把它当成「默认值还在」而误报。
# 第一次写这条断言时正是这么误报的 —— 假阳性同样是坏检查。
if sed 's/#.*$//' "$INSTALL_DSH" | grep -qE 'DSH_VERSION="\$\{DSH_VERSION:-[0-9]'; then
    fail "install_dsh.sh 正文仍有默认 DSH 版本（DIV-0010 未生效）"
else
    ok "install_dsh.sh 正文无默认 DSH 版本（注释里的引用不算）"
fi
if grep -q '必须显式指定 DSH_VERSION' "$INSTALL_DSH" 2>/dev/null; then
    ok "install_dsh.sh 有空值守卫"
else
    fail "install_dsh.sh 缺少空值守卫"
fi
# 未指定版本时必须失败，且退出码是 2（不是 0、不是 1）
rc=0
bash "$INSTALL_DSH" >/dev/null 2>&1 || rc=$?
if [ "$rc" -eq 2 ]; then
    ok "未指定 DSH_VERSION → 退出 2（失败即报错）"
else
    fail "未指定 DSH_VERSION 时退出码是 $rc，期望 2"
fi
# 守卫必须在 npm install 之前 —— 否则会先装一个错版本再报错
guard_line=$(grep -n '必须显式指定 DSH_VERSION' "$INSTALL_DSH" | head -1 | cut -d: -f1)
npm_line=$(grep -n 'npm install' "$INSTALL_DSH" | head -1 | cut -d: -f1)
if [ -n "$guard_line" ] && [ -n "$npm_line" ] && [ "$guard_line" -lt "$npm_line" ]; then
    ok "守卫在 npm install 之前（不会先装错再报错）"
else
    fail "守卫不在 npm install 之前：guard=$guard_line npm=$npm_line"
fi

section "5g. 动态端口与健康检查身份核验（DIV-0014）"

# 这一节测的是一个**真机上实测到**的缺陷：端口被写死成 3080 时，与另一个 DSH
# 应用并存安装会同时坏两件事 —— 新实例因 EADDRINUSE 起不来，而健康检查探到的是
# **别人**的服务，于是界面把「没起来的实例」显示成「DSH 就绪」；
# 首页「启动」按钮的条件又是 `sandboxRunning && !dshReady`，被灰掉之后
# 用户连手动重试的机会都没有。
CONST_KT="common/src/main/java/com/dshbox/app/common/Constants.kt"
POLICY_KT="sandbox-manager/src/main/java/com/dshbox/app/sandbox/DshHealthPolicy.kt"
RUNNER_KT="sandbox-manager/src/main/java/com/dshbox/app/sandbox/SandboxProcessRunner.kt"
MGR_KT="sandbox-manager/src/main/java/com/dshbox/app/sandbox/DefaultSandboxManager.kt"
HC_KT="sandbox-manager/src/main/java/com/dshbox/app/sandbox/HttpHealthChecker.kt"

if grep -qE 'const val DSH_DEFAULT_PORT|const val DSH_BASE_URL' "$CONST_KT"; then
    fail "Constants.kt 仍有写死的端口/地址常量（DIV-0014 未生效）"
else
    ok "Constants.kt 已无写死的端口与地址常量"
fi
if grep -q 'const val DSH_PORT_AUTO = 0' "$CONST_KT" && grep -q 'fun dshBaseUrl(port: Int)' "$CONST_KT"; then
    ok "端口改为「让内核分配」，地址改为按实际端口拼"
else
    fail "缺少 DSH_PORT_AUTO 或 dshBaseUrl(port)"
fi
# 端口必须作为**命令行参数**传下去。DSH 只认 --port，不读 PORT 环境变量：
# 上游设的那个 PORT env 是死代码（webserver 的 port 只来自 ctx.webStartup.port）。
if grep -q -- '--port \$port' "$RUNNER_KT"; then
    ok "dsh 启动命令行带 --port（端口真的传下去了）"
else
    fail "dsh 启动命令行没传 --port —— 端口不会被内核分配"
fi
# ⚠️ 顺序也是判据（2026-10-14 真机失败后补）。`--patch` 必须排在 `--host` / `--port`
# **之前**：dsh 启动器只解析它自己的旗标，遇到第一个不认识的旗标就把剩下的原样转交给
# 被启动的应用，而 --host / --port 属于 web 应用。排在后面时 web 应用会打印
# `error: unknown option '--patch'` 并退出 —— 真机上 DSH 每次启动都死在这里
# （process-dsh.log 连续 6 次）。
# 只查「命令里有没有 --patch」是抓不到它的（坏顺序下照样有），所以必须比下标。
RUNNER_TEST="sandbox-manager/src/test/java/com/dshbox/app/sandbox/SandboxProcessRunnerTest.kt"
ORDER_OK=$(python3 - "$RUNNER_KT" <<'PYORDER'
import sys, pathlib
# 只看代码不看注释：注释里必然会引用这些旗标来解释原因，
# 把注释算进去会让「解释」本身触发误判。
code = "\n".join(
    ln.split("//", 1)[0]
    for ln in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
    if not ln.strip().startswith("*") and not ln.strip().startswith("/*")
)
i_p = code.find("--profile ${Constants.DSH_WEB_PROFILE}")
i_a = code.find("$patches", i_p if i_p >= 0 else 0)
i_h = code.find("--host ${Constants.DSH_DEFAULT_HOST}")
i_t = code.find("--port $port")
print("yes" if 0 <= i_p < i_a < i_h and i_a < i_t else "no")
PYORDER
)
if [ "$ORDER_OK" = "yes" ]; then
    ok "dsh 命令行参数顺序正确：--patch 排在 --host / --port 之前"
else
    fail "dsh 命令行的 --patch 被排到了 --host / --port 之后 —— DSH 会报 unknown option 起不来"
fi
# 回归单测也要有人在位。上面那条源码断言钉的是**源码文本的形状**，
# 而单测钉的是**生成出来的命令字符串** —— 换了写法（比如重排变量）时，
# 前者可能失效，后者仍然成立。两层都要。
if grep -q 'fun dshPatchOverlaysMustPrecedeAppFlags' "$RUNNER_TEST" &&
    grep -q 'patch < shell.indexOf("--host")' "$RUNNER_TEST"; then
    ok "单测钉住参数顺序（钉下标，不钉存在性）"
else
    fail "缺参数顺序的回归单测，或它退化成只查存在性"
fi
if grep -q 'base\["PORT"\]' "$MGR_KT"; then
    fail "DefaultSandboxManager 又设了那个不起作用的 PORT 环境变量"
else
    ok "不起作用的 PORT 环境变量未复活"
fi
if grep -q 'DshWebLaunchParser.parse' "$MGR_KT"; then
    ok "端口/token 从 DSH 自己的输出里解析（不猜）"
else
    fail "没有从 DSH 输出解析端口/token"
fi
if grep -q '!= HTTP_UNAUTHORIZED' "$HC_KT"; then
    ok "健康检查带 token 核验身份（401 = 应答者不是我）"
else
    fail "健康检查没有身份核验 —— 会再把别人的服务当成自己的"
fi
if grep -q 'probe.webUiReady && probe.processAlive' "$POLICY_KT"; then
    ok "Ready 同时要求 webUiReady 与 processAlive"
else
    fail "Ready 只看 webUiReady —— 端口被别人占用时会误报就绪"
fi

# 代码里不得再出现写死的「回环地址:端口」。
# 注释里提到它是**允许**的 —— 那正是解释「为什么不能写死」的地方；
# 把注释也一并禁掉会逼作者删掉解释，方向正好反了。（与 5f 节的注释过滤同理。）
HARD=0
LAST_HIT=""
while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    code="${hit%%//*}"
    t="${code#*:*:}"
    t="${t#"${t%%[![:space:]]*}"}"
    case "$t" in ""|/\**|\**) continue ;; esac
    HARD=$((HARD + 1))
    LAST_HIT="$hit"
done < <(grep -rn '127\.0\.0\.1:3080\|localhost:3080' --include=*.kt */src/main/ 2>/dev/null || true)
if [ "$HARD" -eq 0 ]; then
    ok "src/main 的代码里没有写死的回环地址+端口"
else
    fail "仍有 $HARD 处写死的地址，例如：$LAST_HIT"
fi

# 「地址未知」的占位文案必须在 6 个语言里都存在（键集合必须一致）
MISSING_LANG=""
for lang in values values-zh values-ar values-es values-fr values-ru; do
    if ! grep -q 'name="home_dsh_address_unknown"' "app/src/main/res/$lang/strings.xml" 2>/dev/null; then
        MISSING_LANG="$MISSING_LANG $lang"
    fi
done
if [ -z "$MISSING_LANG" ]; then
    ok "6 个语言都有「地址待定」占位文案"
else
    fail "缺占位文案的语言：$MISSING_LANG"
fi

# 判据「状态码 != 401」只在探测路径是 DSH 打印的那个 URL 时成立 —— 实测
# （DSH 0.2.0-rc.2）：GET /?token=<对> → 303，而 GET /api/health?token=<对> → 401。
# 也就是说，换个看起来更「正规」的健康检查路径，会让判据**永久失败**，
# 退化成上游踩过的另一个坑（一直未就绪 → 120s 超时 → 销毁进程）。
# 所以 healthPath 必须保持 "/"，且不能被别处覆盖。
CFG_KT="sandbox-manager/src/main/java/com/dshbox/app/sandbox/SandboxConfig.kt"
if grep -q 'val healthPath: String = "/"' "$CFG_KT"; then
    ok "healthPath 默认值是 /（与 DSH 打印的 URL 路径一致）"
else
    fail "healthPath 不再是 / —— 401 判据会永久失败，见 HttpHealthChecker 的 KDoc"
fi
OVERRIDE=$(grep -rn 'healthPath *=' --include=*.kt app/src sandbox-manager/src 2>/dev/null \
    | grep -v 'val healthPath' || true)
if [ -z "$OVERRIDE" ]; then
    ok "没有任何地方覆盖 healthPath"
else
    fail "healthPath 被覆盖：$(printf '%s' "$OVERRIDE" | head -2)"
fi

# 反向验证：检查器必须能拦住真实故障。
# 本项目已经吃过三次「检查器自己骗自己」的亏，所以每条断言都要证明它会失败。
cp "$POLICY_KT" "$SCRATCH/policy14.bak"
sed -i 's/if (probe.webUiReady && probe.processAlive) {/if (probe.webUiReady) {/' "$POLICY_KT"
if bash tools/apply-divergences.sh --check >/dev/null 2>&1; then
    fail "注入后 DIV-0014 检查仍通过：拦不住「Ready 只看 webUiReady」"
else
    ok "注入后重放：Ready 判据退回只看 webUiReady 被拦下 exit=1"
fi
cp "$SCRATCH/policy14.bak" "$POLICY_KT"
if bash tools/apply-divergences.sh --check >/dev/null 2>&1; then
    ok "恢复后重放重新通过                                exit=0"
else
    fail "恢复后重放仍失败（备份/还原有问题）"
fi

section "5h. 无障碍服务名与固定签名密钥库（DIV-0016 / DIV-0017）"

# --- DIV-0016：无障碍服务名不能与 DSHBox 撞名 ---
#
# 真机截图（2026-10-14）：系统「无障碍 → 已下载的应用」里出现**两条同名的**
# 「DSHBox 手机助手」，用户无法判断该开哪一个。开错了，DSHFusion 的手机助手
# 通道就不通，而界面上没有任何东西提示这一点。
# 这与 DIV-0011（authority 冲突）是同一类问题 —— 都是「并存安装」这个设计目标
# 在**系统可见身份**上的必然后果，而它是资源文案，前三条身份断言都查不到。
#
# 两个方向都查：残留旧名 = 没改；既无旧名也无新名 = 改成了别的东西。
# 只查「不含 DSHBox」会放过「改成了手机助手」这类丢掉品牌的值。
A11Y_BAD=0
for lang in values values-zh values-ar values-es values-fr values-ru; do
    f="pilot/dshbox-adapter/src/main/res/$lang/strings.xml"
    if [ ! -f "$f" ]; then
        fail "缺少 $f"
        A11Y_BAD=1
        continue
    fi
    v="$(sed -n 's|.*<string name="relay_a11y_label">\(.*\)</string>.*|\1|p' "$f")"
    case "$v" in
        "")          fail "$lang 的 relay_a11y_label 读不出来"; A11Y_BAD=1 ;;
        *DSHBox*)    fail "$lang 的无障碍服务名仍写着 DSHBox：$v"; A11Y_BAD=1 ;;
        *DSHFusion*) : ;;
        *)           fail "$lang 的无障碍服务名既无 DSHBox 也无 DSHFusion：$v"; A11Y_BAD=1 ;;
    esac
done
[ "$A11Y_BAD" = "0" ] \
    && ok "无障碍服务名已在 6 个语言里改为 DSHFusion（不再与 DSHBox 撞名）"

# --- DIV-0017：固定签名密钥库 ---
#
# 真机实测：四个不同 CI 运行的产物签名公钥**两两不同**，于是新构建装不到旧构建
# 上面（INSTALL_FAILED_UPDATE_INCOMPATIBLE），每次更新都得卸载重装，
# 连带丢掉已下好的几百 MB 运行环境层。
#
# ⚠️ 注意这里查的是 **debug 块内**是否有 signingConfig 覆盖。
# 只查「文件里有 signingConfig」是不够的 —— release 块本来就有，
# 那会漏掉「debug 没改」这个真正的缺口（而 CI 构建的正是 debug）。
if python3 - <<'PYEOF'
import re, pathlib, sys
body = pathlib.Path("app/build.gradle.kts").read_text(encoding="utf-8")
m = re.search(r"debug\s*\{(.*?)\n        \}", body, re.S)
if not m:
    print("找不到 debug 构建块", file=sys.stderr); sys.exit(1)
if "signingConfig = if (hasReleaseKeystore)" not in m.group(1):
    print("debug 块没有 signingConfig 覆盖（会退回随机调试密钥）", file=sys.stderr)
    sys.exit(1)
sys.exit(0)
PYEOF
then
    ok "debug 构建类型使用固定密钥库（不再用每次现生成的调试密钥）"
else
    fail "debug 构建类型没有覆盖 signingConfig"
fi

WF=".github/workflows/android.yml"
if [ -f "$WF" ]; then
    WF_BAD=0
    grep -q 'Restore signing keystore' "$WF" || { fail "CI 缺少还原密钥库的步骤"; WF_BAD=1; }
    grep -q 'DSHFUSION_KEYSTORE_B64' "$WF" || { fail "CI 未从 Secret 取密钥库"; WF_BAD=1; }
    grep -q '76d588d44aad12b00aac0a1587738bc91efc182b7c0b00b288a5826058e98126' "$WF" \
        || { fail "CI 未核验证书指纹（「签名稳定」将无法验证）"; WF_BAD=1; }
    # 还原步骤必须排在 assemble 之前，否则等于没还原
    line_restore="$(grep -n 'Restore signing keystore' "$WF" | head -1 | cut -d: -f1)"
    line_asm="$(grep -n 'Assemble debug APK' "$WF" | head -1 | cut -d: -f1)"
    if [ -n "$line_restore" ] && [ -n "$line_asm" ] && [ "$line_restore" -gt "$line_asm" ]; then
        fail "还原密钥库的步骤排在 assemble 之后，等于没生效"
        WF_BAD=1
    fi
    [ "$WF_BAD" = "0" ] && ok "CI 在 assemble 之前从 Secret 还原密钥库并核验指纹"
else
    fail "缺少 $WF"
fi

# 反向：密钥库不得入库。
# ⚠️ 逐行查且跳过注释行 —— 直接 grep 会被 `# *.jks` 骗过
# （apply-divergences.sh 里的第一版就是这么被故障注入抓到的）。
GI_BAD=0
for pat in '*.jks' 'keystore.properties'; do
    if grep -qxF "$pat" .gitignore 2>/dev/null; then
        :
    else
        fail ".gitignore 不再忽略 $pat（密钥有入库风险）"
        GI_BAD=1
    fi
done
if [ "$GI_BAD" = "0" ]; then
    if git ls-files --error-unmatch signing/dshfusion.jks >/dev/null 2>&1 \
       || git ls-files --error-unmatch keystore.properties >/dev/null 2>&1; then
        fail "密钥库或 keystore.properties 已被 git 跟踪（上游约定是绝不入库）"
    else
        ok "密钥库与 keystore.properties 都未被 git 跟踪（上游「绝不入库」的约定得以保留）"
    fi
fi

section "5i. 通知栏动作不被压成「一个字 + 省略号」（DIV-0018）"

# 真机截图（2026-10-14）：DSHFusion 的常驻通知里 4 个动作按钮**全部只显示**
# 「打… / 启… / 重… / 停…」。设备 font_scale = 1.0，不是用户把字调大了。
# 成因是两个因素相乘：**动作个数**（4 个把动作栏平分成四份）与**文案长度**
# （4～6 个字符，ru/es 更长）。所以两边都要查 —— 只查一边，另一边回退时
# 这个 bug 会原样回来。见 governance/divergences.json 的 DIV-0018。
NOTIFY_BAD=0
for lang in values values-zh values-ar values-es values-fr values-ru; do
    f="app/src/main/res/$lang/strings.xml"
    if [ ! -f "$f" ]; then
        fail "缺少 $f"
        NOTIFY_BAD=1
        continue
    fi
    for key in notify_action_open_dsh notify_action_start_dsh \
               notify_action_restart_dsh notify_action_stop_sandbox; do
        v="$(sed -n "s|.*<string name=\"$key\">\(.*\)</string>.*|\1|p" "$f")"
        case "$v" in
            "") fail "$lang 的 $key 读不出来"; NOTIFY_BAD=1 ;;
            *" DSH"*|*沙箱*|*sandbox*|*الصندوق*|*"entorno aislado"*|*"bac à sable"*|*песочницу*)
                fail "$lang 的 $key 仍是长文案：$v"; NOTIFY_BAD=1 ;;
        esac
    done
done
[ "$NOTIFY_BAD" = "0" ] \
    && ok "6 个语言的 4 条通知动作文案都已改短（不再含旧的长文案）"

# 结构那一半：动作个数必须由状态决定，不能是写死的 4 个。
# ⚠️ 这里查的是「恰好 1 处 addAction」而不是「0 处」—— 修复后的写法是
# 在循环里调用一次、文案由 labelRes 决定。第一版断言写成「0 处」，
# 结果把**正确的实现**判成了失败（见本次提交的说明）。
if python3 - <<'PYEOF'
import re, pathlib, sys
p = pathlib.Path("app/src/main/java/com/dshbox/app/service/SandboxService.kt")
body = p.read_text(encoding="utf-8")
if "for (action in dshNotifyActions(dshState))" not in body:
    print("buildNotification 没有按 dshNotifyActions(dshState) 循环装配动作", file=sys.stderr)
    sys.exit(1)
n = len(re.findall(r"\.addAction\(", body))
if n != 1:
    print(f"addAction 调用应恰好 1 处（循环内），实际 {n} 处", file=sys.stderr)
    sys.exit(1)
for line in body.splitlines():
    if "addAction" in line and "notify_action_" in line:
        print("addAction 又写死了具体文案：" + line.strip()[:60], file=sys.stderr)
        sys.exit(1)
sys.exit(0)
PYEOF
then
    ok "通知栏动作由 dshNotifyActions(dshState) 决定（仅 1 处 addAction，文案不写死）"
else
    fail "通知栏动作装配没有收敛到状态函数"
fi

# 纯函数本身在不在、单测在不在。单测断言的是四条性质，
# 不是「实现长什么样」—— 那样写的话重构一次就得改测试。
NA_KT="app/src/main/java/com/dshbox/app/service/DshNotifyActions.kt"
NA_TEST="app/src/test/java/com/dshbox/app/service/DshNotifyActionsTest.kt"
if [ -f "$NA_KT" ] && [ -f "$NA_TEST" ]; then
    missing_assert=""
    for needle in "neverMoreThanThreeActions" "openAndStartAreMutuallyExclusive" \
                  "stopIsAlwaysPresentAndLast" "notRunningOffersStartThenStop"; do
        grep -q "fun $needle" "$NA_TEST" || missing_assert="$missing_assert $needle"
    done
    if [ -n "$missing_assert" ]; then
        fail "DshNotifyActionsTest 少断言：$missing_assert"
    else
        ok "DshNotifyActions 纯函数与四条性质单测都在场"
    fi
else
    fail "缺少 DshNotifyActions.kt 或其单测"
fi

section "5j. v5 备份归档的格式与预检（阶段 2.1 / DIV-0019）"

# 阶段 2 的第一块：从 DSHA 原样导入 v5 容器，并加上本项目的预检门面。
# 这一节查四件事，缺任何一件都会以不同方式静默失败：
#   ① 模块接入（不 include = 单测一次都不跑）
#   ② Gson 下限（<2.11 时 Strictness 消失 = 重复键不再被拒，编译期不报错）
#   ③ 导入副本的来源头与正文（署名义务 + 「原样」必须可验证）
#   ④ 预检断言钉的是**具体错误码**，不是「抛了异常就行」
BK_BAD=0

if grep -q 'include(":backup")' settings.gradle.kts 2>/dev/null; then
    ok "settings.gradle.kts 已包含 :backup 模块"
else
    fail "settings.gradle.kts 没有 include(\":backup\") —— 该模块的单测不会被 CI 执行"
    BK_BAD=1
fi

if [ -f backup/build.gradle.kts ] && grep -q "libs.gson" backup/build.gradle.kts; then
    ok "backup/build.gradle.kts 存在且依赖 libs.gson"
else
    fail "backup/build.gradle.kts 缺失或没有依赖 libs.gson"
    BK_BAD=1
fi

# Gson 下限：BackupJson 用 Strictness.STRICT 拒绝重复键。低于 2.11 没有这个 API，
# 退化是静默的（只在恶意输入下才看得出来），所以必须显式卡住。
GSON_VER="$(sed -n 's/^gson *= *"\([^"]*\)".*/\1/p' gradle/libs.versions.toml)"
if [ -z "$GSON_VER" ]; then
    fail "版本目录里没有 gson 版本条目"
    BK_BAD=1
elif python3 - "$GSON_VER" <<'PYEOF'
import sys
parts = sys.argv[1].split(".")
try:
    major, minor = int(parts[0]), int(parts[1])
except (IndexError, ValueError):
    sys.exit(1)
sys.exit(0 if (major, minor) >= (2, 11) else 1)
PYEOF
then
    ok "Gson $GSON_VER 不低于 2.11（Strictness 可用）"
else
    fail "Gson $GSON_VER 低于 2.11 —— 重复键将不再被拒，且编译期不会报错"
    BK_BAD=1
fi

# 导入副本的逐字节核验交给专门的检查器（它的期望值内嵌在仓库里，
# 因此在 CI 中是真跑，而不是像骨干保真度检查那样走「跳过」分支）。
if python3 tools/check-dsha-imports.py >/dev/null 2>&1; then
    ok "DSHA 导入副本来源头与正文逐字节一致（$(python3 -c 'import json;print(len(json.load(open("governance/dsha-imports.json"))["files"]))') 个文件）"
else
    fail "DSHA 导入副本核验失败，详见 python3 tools/check-dsha-imports.py"
    BK_BAD=1
fi

# 预检的负面用例必须钉**具体错误码**。阶段 2 的验收是「故意投喂损坏备份 →
# 明确报错」——「明确」就是这个：用户拿到的得是能据以处理的分类。
V5_TEST="backup/src/test/java/com/dshbox/app/backup/V5ArchiveTest.kt"
V5_KT="backup/src/main/java/com/dshbox/app/backup/V5Archive.kt"
if [ -f "$V5_TEST" ] && [ -f "$V5_KT" ]; then
    missing_code=""
    for code in PAYLOAD_CHECKSUM ARCHIVE_FOOTER ARCHIVE_VERSION FALSE_COMPLETE \
                ROOT_SCOPE_MISMATCH MANIFEST_PAYLOAD_MISMATCH; do
        grep -q "assertEquals(\"$code\"" "$V5_TEST" || missing_code="$missing_code $code"
    done
    if [ -n "$missing_code" ]; then
        fail "V5ArchiveTest 没有钉住具体错误码：$missing_code"
        BK_BAD=1
    else
        ok "预检负面用例钉住了 6 个具体错误码（不是「抛了异常就行」）"
    fi
    # forge 的正向对照：没有它，那些负面用例会因为「夹具坏了」而红，
    # 结论就不可信（手写组装的容器本身必须能被读取侧接受）。
    if grep -q "fun forgeProducesAContainerTheReaderAccepts" "$V5_TEST"; then
        ok "手写组装的恶意容器有正向对照，负面用例的结论可信"
    else
        fail "V5ArchiveTest 缺少 forge 的正向对照"
        BK_BAD=1
    fi
else
    fail "缺少 V5Archive.kt 或 V5ArchiveTest.kt"
    BK_BAD=1
fi

[ "$BK_BAD" = "0" ] && ok "v5 归档模块接入与预检断言齐备"

section "5k. 备份模块契约：测试与容器不能各说各话（阶段 2.1）"
BC_BAD=0
# 这一节的存在理由是一个真实事故：测试里把魔数手抄成 7 字符的 "DSHDATA"，
# 而容器是 8 字符的 "DSHADATA"，5 条用例同时红。本机其他检查一条都发现不了。
if python3 tools/check-backup-contract.py >/dev/null 2>&1; then
    ok "契约核验通过：魔数三处一致、测试未手抄魔数、断言的错误码均有效"
else
    fail "备份模块契约核验失败 —— 跑 python3 tools/check-backup-contract.py 看明细"
    BC_BAD=1
fi
# 注入自证：把检查器最该抓的那件事真的做一遍，它必须红。
# （第 16 条事故教过：没生效的注入会给出完全虚假的安全感。）
if [ -f "$V5_TEST" ]; then
    probe=$(mktemp)
    cp "$V5_TEST" "$probe"
    before=$(sha256sum "$V5_TEST" | cut -c1-16)
    # 无反引用写法：`\1` 在生成这个脚本的 Python 里会被吃成控制字符
    sed -i 's/out\.write(magic)/out.write("DSHDATA".toByteArray(Charsets.US_ASCII))/' "$V5_TEST"
    after=$(sha256sum "$V5_TEST" | cut -c1-16)
    if [ "$before" = "$after" ]; then
        fail "契约检查的注入自证没生效（文件根本没变）—— 这条检查本身不可信"
        BC_BAD=1
    elif python3 tools/check-backup-contract.py >/dev/null 2>&1; then
        fail "注入手抄魔数后契约核验仍然通过 —— 这条检查抓不到它该抓的缺陷"
        BC_BAD=1
    else
        ok "注入手抄魔数后契约核验确实变红（注入已自证生效并复原）"
    fi
    cp "$probe" "$V5_TEST"
    rm -f "$probe"
    after2=$(sha256sum "$V5_TEST" | cut -c1-16)
    [ "$before" = "$after2" ] || { fail "注入后未能复原 V5ArchiveTest.kt"; BC_BAD=1; }
else
    fail "缺少 V5ArchiveTest.kt，无法做注入自证"
    BC_BAD=1
fi

[ "$BC_BAD" = "0" ] && ok "备份模块契约与注入自证齐备"

section "5l. 备份范围与排除清单（阶段 2.2）"
# 这一节的每一条都对应一种「看起来成功、其实数据没了」的失败。
SC_BAD=0
SC_SCOPE="backup/src/main/java/com/dshbox/app/backup/DshBackupScope.kt"
SC_EXCL="backup/src/main/java/com/dshbox/app/backup/DshBackupExclusions.kt"
if [ -f "$SC_SCOPE" ] && [ -f "$SC_EXCL" ]; then
    # ① 部分备份绝不叫全量的名字：老版本会按全量恢复，清掉配置与插件
    if grep -q '"DSHFusion-backup-"' "$SC_SCOPE" && grep -q 'isFull' "$SC_SCOPE"; then
        ok "范围前缀与 isFull 都在场（部分备份不会叫全量名字）"
    else
        fail "DshBackupScope 缺少全量前缀或 isFull 判定"
        SC_BAD=1
    fi
    # ② PLUGINS 范围必须按本仓库布局校正，不能照搬 DSHA 那四个不存在的目录
    dsha_only=0
    for name in plugin-src plugin-sources.json plugin-history plugin-safe-mode.json; do
        grep -q "\"$name\"" "$SC_SCOPE" && dsha_only=1
    done
    if [ "$dsha_only" = "0" ] && grep -q '"\.dsh/profiles"' "$SC_SCOPE"; then
        ok "插件范围用的是本仓库布局 .dsh/profiles（没照搬 DSHA 专有目录）"
    else
        fail "插件范围仍含 DSHA 专有目录，或缺少 .dsh/profiles"
        SC_BAD=1
    fi
    # ③ 排除清单不能把 .dsh/dshbox 整目录排掉 —— 那会丢掉三份承载状态的 overlay，
    #    其中「哪些插件被停用」是安全机制（真机上出过「每次都起不来」）。
    if grep -q '".dsh/dshbox"' "$SC_EXCL" && ! grep -q 'REGENERATED = listOf(.dsh/dshbox.)' "$SC_EXCL"; then
        fail "排除清单里出现了 .dsh/dshbox 整目录"
        SC_BAD=1
    fi
    if grep -q 'MUST_KEEP' "$SC_EXCL" && grep -q 'checkInvariants' "$SC_EXCL"; then
        ok "排除清单区分「可再生」与「承载状态」，并有可执行的结构不变式"
    else
        fail "排除清单缺少 MUST_KEEP 或 checkInvariants"
        SC_BAD=1
    fi
    # ④ .dsh/bin 没有命名空间，可能装着用户自己的东西，不能排除
    if grep -qE '^\s*"\.dsh/bin",?\s*$' "$SC_EXCL"; then
        fail "排除清单把 .dsh/bin 排掉了（可能静默丢掉用户自己放的东西）"
        SC_BAD=1
    else
        ok ".dsh/bin 未被排除（它没有命名空间，可能是用户自己的目录）"
    fi
    # ⑤ 排除逻辑与范围一一对应的性质必须有单测
    for t in DshBackupScopeTest DshBackupExclusionsTest; do
        if [ -f "backup/src/test/java/com/dshbox/app/backup/$t.kt" ]; then
            ok "$t 在场"
        else
            fail "缺少 $t"
            SC_BAD=1
        fi
    done
    # ⑥ 改写后移植必须如实登记（不能塞进逐字节副本清单）
    if python3 -c "
import json,sys
d=json.load(open('governance/dsha-imports.json'))
items=d.get('derived',{}).get('items',[])
paths={e['local_path'] for e in d['files']}
assert items, 'derived 为空'
assert not ({i['local_path'] for i in items} & paths), '两种身份重叠'
assert all(i.get('kept') and i.get('changed') for i in items), '没写清保留/改掉了什么'
" 2>/dev/null; then
        ok "改写后移植已登记，且写清了保留与改动（与逐字节副本身份不重叠）"
    else
        fail "derived 登记缺失或不合规"
        SC_BAD=1
    fi
else
    fail "缺少 DshBackupScope.kt 或 DshBackupExclusions.kt"
    SC_BAD=1
fi

[ "$SC_BAD" = "0" ] && ok "备份范围与排除清单齐备"


section "5m. 凭据投影：机器本地记录不进归档（阶段 2.3）"
# 这一节和前几节**性质不同**，值得说明：
#   5j/5k/5l 查的是「文件在不在、常量一致不一致、字符串在不在」。
#   而 2.3 要证明的性质是**行为** —— 「client-connection/ 的记录不进归档，
#   而 provider 凭据一个字节不动」。字符串在、行为错，照样能过 grep。
#   所以这一节**真编译、真运行**（本机有 JDK 17；`java`/`javac` 都在）。
#   本机**没有 kotlinc**，所以 .kt 的测试跑不了 —— 那是 android.yml 的事。
CR_BAD=0
CR_PROJ="backup/src/main/java/com/deepseekharness/app/backup/CredentialProjection.java"
CR_SRC="backup/src/main/java/com/deepseekharness/app/backup/CredentialFileBackupSource.java"
for f in "$CR_PROJ" "$CR_SRC"; do
    if [ -f "$f" ]; then
        ok "凭据投影源码在场：$(basename "$f")"
    else
        fail "缺少 $f"
        CR_BAD=1
    fi
done

# ① 投影必须认 DSH **自己的**记录键前缀。这不是我们的发明：
#    dsh-client-connection 里 `credentialKey("client-connection", "browser-session")`
#    就是那个浏览器会话授权 —— 它是**机器本地**的，跟到另一台设备上没有意义。
if grep -q '"client-connection/"' "$CR_PROJ" 2>/dev/null; then
    ok "投影认的是 DSH 自己的机器本地前缀 client-connection/（与 dsh-client-connection 一致）"
else
    fail "CredentialProjection 里找不到 client-connection/ 前缀"
    CR_BAD=1
fi

# ② 新增依赖必须三处一致：版本目录、模块声明、署名登记。
#    漏掉任何一处，「能编过」与「合法分发」就是两件事。
if grep -q '^snakeyaml = "2.4"' gradle/libs.versions.toml \
   && grep -q 'snakeyaml = { group = "org.yaml"' gradle/libs.versions.toml \
   && grep -q 'implementation(libs.snakeyaml)' backup/build.gradle.kts; then
    ok "SnakeYAML 在版本目录与模块声明里（且版本一致）"
else
    fail "SnakeYAML 的版本目录条目或模块声明缺失/版本不一致"
    CR_BAD=1
fi
if grep -q '| SnakeYAML | 2.4 |' governance/ATTRIBUTION.md; then
    ok "SnakeYAML 已登记进署名表（Apache-2.0）"
else
    fail "SnakeYAML 没有登记进 governance/ATTRIBUTION.md"
    CR_BAD=1
fi

# ③ 混淆保险：SnakeYAML 的 jar **不自带** consumer proguard 规则（已解包核对），
#    而本仓库 release 开了混淆、上游 DSHA 的 release 没开 ——
#    所以「DSHA 装了它能跑」不构成证据。
if grep -q '^-keep class org.yaml.snakeyaml' app/proguard-rules.pro; then
    ok "app 侧有 SnakeYAML 的 keep 规则（保险；release 混淆路径仍未验证）"
else
    fail "app/proguard-rules.pro 里缺少 SnakeYAML 的 keep 规则"
    CR_BAD=1
fi

# ④ 真正跑一遍。这是本节唯一能证明**行为**的一步。
if [ -f tools/run-backup-jvm-tests.sh ]; then
    # 用 mktemp 而不是写死一个临时文件路径：写死会同时踩两件事 ——
    # ① 并发运行时互相覆盖；② tools/privacy_audit.py 会把仓库里写死的临时
    #    目录路径当成「本机痕迹」判失败（那条规则的本意是防发布时带出本机路径）。
    JVM_LOG=$(mktemp)
    if bash tools/run-backup-jvm-tests.sh > "$JVM_LOG" 2>&1; then
        JVM_TESTS=$(grep -o 'OK ([0-9]* tests)' "$JVM_LOG" | grep -o '[0-9]*' | head -1)
        ok "本机真编译真运行 :backup 的 Java 测试与凭据投影行为核验通过（${JVM_TESTS:-?} 条）"
    else
        fail "本机 JVM 测试或凭据投影核验失败 —— 详见下面的输出"
        sed -n '1,25p' "$JVM_LOG" >&2
        CR_BAD=1
    fi
    rm -f "$JVM_LOG"
else
    fail "缺少 tools/run-backup-jvm-tests.sh"
    CR_BAD=1
fi

# ⑤ 2.3 删掉的那条上游测试必须真的**恢复回来**，而不是只在纸上写着「以后补」。
#    它证明的性质（机器记录在候选阶段就被投影）在 2.3 **没有被验证**；
#    2.4 摘掉那条用不上的 Android 快路径之后，这条链变成纯 JVM，于是它能在本机真跑。
#    这一条断言同时钉三件事：方法在文件里、登记写清了它被删过又恢复、以及那个
#    「未导入」条目已经撤掉（留着它就是登记在撒谎）。
if python3 -c "
import json,sys,pathlib
d=json.load(open('governance/dsha-imports.json'))
it=[i for i in d['derived']['items'] if i['local_path'].endswith('CredentialProjectionTest.java')]
assert it, '没有登记 CredentialProjectionTest 的适配'
blob=' '.join(it[0].get('changed',[]))
name='oldV5ArchiveMachineRecordIsProjectedInCandidateBeforeItCanBecomeActive'
assert name in blob, '那条测试的删除/恢复经过没写进 changed'
assert '恢复' in blob, '没写清它已在 2.4 恢复'
src=pathlib.Path('backup/src/test/java/com/deepseekharness/app/backup/CredentialProjectionTest.java').read_text(encoding='utf-8')
assert name in src, '登记说恢复了，但文件里根本没有这个方法'
assert src.count('@Test')==7, f\"@Test 条数是 {src.count('@Test')}，期望 7\"
ni=[e for e in d.get('not_imported',{}).get('items',[]) if 'oldV5Archive' in str(e.get('upstream_path',''))]
assert not ni, '那条测试已经恢复了，not_imported 里却还留着「未导入」'
" 2>/dev/null; then
    ok "2.3 删掉的那条上游测试已在 2.4 真恢复（文件里有、7 条 @Test、not_imported 已撤）"
else
    fail "那条被删测试的恢复不完整（登记说恢复了、文件里没有，或 not_imported 没撤）"
    CR_BAD=1
fi

# ⑥ 本仓库自写的测试必须单独登记，且与上游身份不重叠。
if python3 -c "
import json
d=json.load(open('governance/dsha-imports.json'))
o=d.get('original',{}).get('items',[])
assert o, 'original 段为空'
paths={e['local_path'] for e in d['files']}|{i['local_path'] for i in d['derived']['items']}
assert not ({i['local_path'] for i in o} & paths), '自写文件与上游移植身份重叠'
assert all(i.get('why') and i.get('verified_by') for i in o), '自写文件没写清为什么/谁来核'
" 2>/dev/null; then
    ok "本仓库自写的测试单独登记（与上游移植身份不重叠）"
else
    fail "original 段缺失或不合规"
    CR_BAD=1
fi

[ "$CR_BAD" = "0" ] && ok "凭据投影齐备且行为已核验"



section "5n. 恢复核心：预检与候选构建（阶段 2.4）"

# 为什么这一节必须存在：
# 2.4 搬的是**会毁数据**的那条路径（候选构建 + 回滚）。它的安全性全压在
# 「校验失败时一个字节都没写进活动目录」上，所以「登记写对了」远远不够 ——
# 必须有一条断言证明这些测试**真的在本机跑起来了**。

# ① 三个新的逐字节副本与两个适配副本都登记了，且上游侧已核。
#    用 mktemp 而不是写死一个临时文件路径：写死会同时踩两件事 ——
#    ① 并发运行时互相覆盖；② tools/privacy_audit.py 会把仓库里写死的临时
#       目录路径当成「本机痕迹」判失败（那条规则的本意是防发布时带出本机路径）。
#    这一条我自己刚踩过：5n 节第一版在这里写死了一个临时文件路径，被隐私审计抓了出来。
D_LOG=$(mktemp)
if python3 tools/check-dsha-imports.py > "$D_LOG" 2>&1; then
    ok "导入登记核验通过：$(grep -o '20 个文件、[0-9]* 字节' "$D_LOG" | head -1)"
else
    fail "导入登记核验失败 —— 见 tools/check-dsha-imports.py 输出"
    cat "$D_LOG" >&2
    CR_BAD=1
fi
rm -f "$D_LOG"

# ② BackupTree 的适配必须**真的把 Android 那条链摘干净了**。
#    只写「已适配」而正文里还留着 AndroidTreeFileSystem，是登记在撒谎。
#    同时钉住**通用遍历的那条不变式还在**：摘掉快路径不能顺手把限额记账也删掉。
if python3 -c "
import pathlib
t=pathlib.Path('backup/src/main/java/com/deepseekharness/app/backup/BackupTree.java').read_text(encoding='utf-8')
for bad in ('AndroidTreeFileSystem','AndroidBackupFileSystem','loadLibrary','native '):
    assert bad not in t, f'BackupTree 里还留着 {bad}'
for keep in ('ENTRY_LIMIT','INVALID_CHILD','SPECIAL_FILE','SOURCE_CHANGED','TreeHash','digestTree'):
    assert keep in t, f'摘快路径时把 {keep} 也删掉了'
" 2>/dev/null; then
    ok "BackupTree 已摘净 Android 快路径，通用遍历的不变式仍在"
else
    fail "BackupTree 的适配不干净：要么还留着 Android 引用，要么把通用逻辑一起删了"
    CR_BAD=1
fi

# ③ 那条用不上的 Android 链与 NativeRestoreTargets 必须登记为**不导入**，
#    而且理由必须点名真正的原因（缺原生库 / 依赖 DSHA 自己的设置子系统），
#    不能只写一句「不需要」——那等于把决定藏起来。
if python3 -c "
import json
d=json.load(open('governance/dsha-imports.json'))
ni={e['upstream_path']: e for e in d['not_imported']['items']}
tgt=[e for k,e in ni.items() if 'NativeRestoreTargets' in k]
assert tgt, 'NativeRestoreTargets 没有登记为不导入'
assert 'ConfigStore' in tgt[0]['reason'], '没写清它依赖 DSHA 自己的设置子系统'
assert '2.4b' == tgt[0].get('phase'), '没写清对应实现放到哪个阶段'
nd=[e for k,e in ni.items() if 'NativeStorage' in k]
assert nd, 'NativeStorage/AndroidTreeFileSystem 没有登记为不导入'
assert 'libtermux' in nd[0]['reason'] and 'UnsatisfiedLinkError' in nd[0]['reason'], \
    '没写清真正的原因：缺 libtermux 这个原生库'
" 2>/dev/null; then
    ok "Android 快路径链与 NativeRestoreTargets 都登记为不导入，理由点名了真正的原因"
else
    fail "不导入的登记不完整或理由没点名原因"
    CR_BAD=1
fi

# ④ 真正跑一遍，并**证明恢复核心真的在跑**。
#    这里不能用「退出码 0」交差 —— 见 docs/INCIDENTS.md 第 26 条：
#    「套件通过」说的是「没有失败发生」，不是「有检查发生过」。
#    所以既数条数（≥42），也确认测试源文件本身在编译清单里。
if [ -f tools/run-backup-jvm-tests.sh ]; then
    R_LOG=$(mktemp)
    if bash tools/run-backup-jvm-tests.sh > "$R_LOG" 2>&1; then
        R_TESTS=$(grep -o 'OK ([0-9]* tests)' "$R_LOG" | grep -o '[0-9]*' | head -1)
        R_CLASSES=$(grep -o '测试源码（.java，[0-9]* 个）' "$R_LOG" | grep -o '[0-9]*' | head -1)
        if [ -n "$R_TESTS" ] && [ "$R_TESTS" -ge 42 ]; then
            ok "本机真编译真运行恢复核心：${R_TESTS} 条 Java 测试（${R_CLASSES} 个测试类，含 NativeRestorePlanTest）"
        else
            fail "本机 Java 测试条数是 ${R_TESTS:-?}，期望 ≥42（恢复核心的测试没跑起来？）"
            CR_BAD=1
        fi
        # 测试条数够了不代表**新那 8 条**在里面：单独钉住这个类。
        if grep -q 'NativeRestorePlanTest' "$R_LOG" || [ -f backup/src/test/java/com/deepseekharness/app/backup/NativeRestorePlanTest.java ]; then
            N8=$(grep -c '@Test' backup/src/test/java/com/deepseekharness/app/backup/NativeRestorePlanTest.java)
            if [ "$N8" = "8" ]; then
                ok "NativeRestorePlanTest 有 8 条 @Test（与上游一致）"
            else
                fail "NativeRestorePlanTest 的 @Test 条数是 $N8，期望 8"
                CR_BAD=1
            fi
        else
            fail "NativeRestorePlanTest 不在仓库里"
            CR_BAD=1
        fi
    else
        fail "本机 JVM 测试失败 —— 详见下面的输出"
        sed -n '1,30p' "$R_LOG" >&2
        CR_BAD=1
    fi
    rm -f "$R_LOG"
else
    fail "缺少 tools/run-backup-jvm-tests.sh"
    CR_BAD=1
fi

# ⑤ 归档里不许出现 API Key / bridge token —— 这一条在 2.3 已经钉过，
#    2.4 加了恢复路径之后必须**再钉一次**：恢复会把候选写进活动目录，
#    是「凭据被写回去」最可能的入口。
if grep -q '凭据投影' backup/README.md; then
    ok "backup/README.md 记录了凭据投影这条约束"
else
    fail "backup/README.md 没有记录凭据投影约束"
    CR_BAD=1
fi



section "5o. 恢复落点：逻辑根 → 本机路径（阶段 2.4b）"

# 为什么这一节必须存在：
# 2.4b 的落点是**我们自写**的 —— DSHA 的 NativeRestoreTargets 不可复用（它的
# target() 第一句就读 root 的 logicalKind，而我们的根只有 id/scope/external）。
# 「自写」意味着**没有上游哈希可核**，所以这里只能核行为：
# ① 它认的是我们自己的归档词汇；② 端到端往返真的跑通；③ 放进来的东西真的会被拒绝。

MAPPING="backup/src/main/java/com/dshbox/app/backup/HostRestoreMapping.java"
MAPPING_TEST="backup/src/test/java/com/dshbox/app/backup/HostRestoreMappingTest.java"

# ① 映射在场，而且它没有把 DSHA 那套词汇顺手抄进来
if [ -f "$MAPPING" ] && grep -q 'implements NativeRestorePlan.Mapping' "$MAPPING"; then
    ok "恢复落点映射在场，并实现了 NativeRestorePlan.Mapping"
else
    fail "缺少 $MAPPING 或它没有实现 NativeRestorePlan.Mapping"
    CR_BAD=1
fi
# 反面：它必须是「我们自己的」映射，不是 DSHA 那套的改写。DSHA 的词汇是
# dsh-child / logicalKind / ConfigStore —— 出现任何一个都说明方向错了。
# 只看**代码**，不看注释：这个文件的 javadoc 里必须能写「DSHA 用的是 logicalKind，
# 我们不是」—— 否则审计结论就没地方放。第一版没剔注释，于是这条断言被一个**提及**
# 骗过（它红了，但红的原因是错的）。剔除 `//`、`/* */` 与 javadoc 的 `*` 行。
if grep -vE '^[[:space:]]*(\*|//|/\*)' "$MAPPING" \
     | grep -qE 'logicalKind|dsh-child|dsh-home|ConfigStore'; then
    fail "映射的**代码**里出现了 DSHA 的逻辑根词汇（logicalKind/dsh-child/dsh-home/ConfigStore）—— 方向错了"
    CR_BAD=1
else
    ok "映射的代码用的是本仓库的成员词汇（注释里的对比说明不算）"
fi
# 落点必须是 user-data/.dsh，而且它是**推导**出来的（mergeSubdir 没有第二个来源）
if grep -q '"\.dsh"' "$MAPPING" && grep -q 'mergeSubdir()' "$MAPPING"; then
    ok "落点是 user-data/.dsh，且 mergeSubdir 由 path 推导（同一事实不写两遍）"
else
    fail "映射没有钉住 .dsh 落点，或 mergeSubdir 变成了第二份来源"
    CR_BAD=1
fi

# ② 端到端往返：本机真编译真跑（2.1 → 2.4a → 2.4b 第一次串起来）
M_LOG=$(mktemp)
if bash tools/run-backup-jvm-tests.sh > "$M_LOG" 2>&1; then
    M_TOTAL=$(grep -o 'OK ([0-9]* tests)' "$M_LOG" | grep -o '[0-9]*' | head -1)
    ok "本机真跑恢复落点：${M_TOTAL:-0} 条 Java 测试（含端到端往返）"
    if [ "${M_TOTAL:-0}" -lt 62 ]; then
        fail "本机 Java 测试只有 ${M_TOTAL:-0} 条，低于阶段 2.4b 的预期 62 条（2.4b-① 53 + 2.4b-② 9）"
        CR_BAD=1
    fi
else
    fail "本机真编译真运行 :backup 失败 —— 见 tools/run-backup-jvm-tests.sh 输出"
    cat "$M_LOG" >&2
    CR_BAD=1
fi
rm -f "$M_LOG"
# 单独钉住新测试类的条数：总数够了不代表新的那几条在里面（事故表第 26 条）。
M_COUNT=$(grep -c '@Test' "$MAPPING_TEST" 2>/dev/null || echo 0)
if [ "$M_COUNT" = "11" ]; then
    ok "HostRestoreMappingTest 有 11 条 @Test"
else
    fail "HostRestoreMappingTest 有 $M_COUNT 条 @Test，期望 11"
    CR_BAD=1
fi
# 「拒绝改动过的活动目录」这条必须真的存在 —— 它是写入路径最基本的自我保护
if grep -q 'TARGET_CHANGED' "$MAPPING_TEST"; then
    ok "有「预检后活动目录被改动 → 拒绝」的注入用例"
else
    fail "缺少「活动目录被改动就必须拒绝」的用例"
    CR_BAD=1
fi
# 成员表在本机（没有 kotlinc）只能复制一份，所以必须有一条断言盯着两边一致
if grep -q 'check-backup-contract.py' tools/verify-phase0.sh 2>/dev/null &&
   python3 tools/check-backup-contract.py >/dev/null 2>&1; then
    ok "成员表与 DshBackupScope 的一致性由 check-backup-contract.py 核验"
else
    fail "check-backup-contract.py 未通过 —— 成员表可能已经和 DshBackupScope 漂移"
    CR_BAD=1
fi

# ③ 注入自证：放宽「谁是上游」之后，检查器**不能**变成瞎的。
#    上面把 original 里的自写文件从反向扫描里减掉了；一个既不在 files、
#    也不在 derived、也不在 original 里的 .java 仍然必须变红。
PROBE="backup/src/main/java/com/dshbox/app/backup/UnregisteredProbe.java"
if [ -e "$PROBE" ]; then
    fail "注入前 $PROBE 就已存在 —— 上一次注入没有清理干净"
    CR_BAD=1
else
    printf 'package com.dshbox.app.backup;\npublic final class UnregisteredProbe {}\n' > "$PROBE"
    if python3 tools/check-dsha-imports.py >/dev/null 2>&1; then
        fail "放进未登记的 .java 后导入核验竟然还是通过 —— 反向扫描已经被放宽成瞎的"
        CR_BAD=1
    else
        ok "未登记的 .java 仍被判失败（放宽只限于「谁是上游」，没有变瞎）"
    fi
    rm -f "$PROBE"
    if python3 tools/check-dsha-imports.py >/dev/null 2>&1; then
        ok "移除注入后导入核验重新通过"
    else
        fail "移除注入后导入核验仍未通过 —— 注入破坏了工作区状态"
        CR_BAD=1
    fi
fi

# ④ 注入自证：成员表漂移必须被判失败。
#    「有一条检查」不等于「那条检查会红」（事故表第 16/18/24 条）。
#    这里把 Java 镜像表里一个成员的 path 改掉，检查器必须报出来；然后复原。
MEMBER_TEST="backup/src/test/java/com/dshbox/app/backup/HostRestoreMappingTest.java"
MEMBER_BAK=$(mktemp)
cp "$MEMBER_TEST" "$MEMBER_BAK"
if python3 - "$MEMBER_TEST" <<'INJECT'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
t = p.read_text(encoding="utf-8")
a = 'new HostRestoreMapping.Member("dsh-profiles", ".dsh/profiles", "plugins")'
b = 'new HostRestoreMapping.Member("dsh-profiles", ".dsh/plugins", "plugins")'
if t.count(a) != 1:
    sys.exit(2)
p.write_text(t.replace(a, b), encoding="utf-8")
INJECT
then
    if python3 tools/check-backup-contract.py >/dev/null 2>&1; then
        fail "成员表被改坏后契约核验竟然还是通过 —— 那条检查没有在真的比对"
        CR_BAD=1
    else
        ok "成员表漂移被判失败（注入已自证生效并复原）"
    fi
else
    fail "成员表注入失败（锚点没命中）—— 这条自证本身无效"
    CR_BAD=1
fi
cp "$MEMBER_BAK" "$MEMBER_TEST"
# 复原必须**逐字节**核对，不能只看「检查又绿了」——
# 绿了也可能是因为别的原因，而文件已经被写坏。（我第一版在这里写了一个
# 把文件跟自己比的假断言，等于什么都没查。）
if ! cmp -s "$MEMBER_BAK" "$MEMBER_TEST"; then
    fail "复原后 $MEMBER_TEST 与备份不一致 —— 注入破坏了工作区状态"
    CR_BAD=1
elif python3 tools/check-backup-contract.py >/dev/null 2>&1; then
    ok "复原后契约核验重新通过，且文件与备份逐字节一致"
else
    fail "复原后契约核验仍未通过"
    CR_BAD=1
fi
rm -f "$MEMBER_BAK"

# 4b 仍未实现「提交」：这一条要如实留在文档里，不能因为测试绿了就当成做完了
if grep -q '原子性' docs/FUSION-PLAN.md; then
    ok "FUSION-PLAN 如实登记了「候选树与活动树之间的原子性未验证」"
else
    fail "FUSION-PLAN 没有登记 2.4b 的未验证项"
    CR_BAD=1
fi

section "5p. 提交与回切：候选树换进活动目录（阶段 2.4b-②）"

# 为什么这一节必须存在：
# 这是本模块**唯一真的会动活动目录**的代码。2.4a 的 buildCandidates 只写
# task/candidate/，一次都不碰活动目录 —— 安全，但也因此没有提交。
#
# ⚠️ 本轮把这一段**从手写换成了移植**。我原先手写了 350 行 HostRestoreCommit，
# 理由是「上游那个类依赖它自己的 ConfigStore/KeyVault」—— **那个理由是错的**，
# 我写下它的时候没有读那个文件。实测它只有 java.io/java.util 两个 import，
# 就在可达范围内，而且更严密。已删除手写的那一份，不保留两套。
# 见 docs/INCIDENTS.md 第 33 条。

TXN="backup/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java"
TXN_TEST="backup/src/test/java/com/deepseekharness/app/backup/HostDataTransactionTest.java"
ADAPTER="backup/src/main/java/com/dshbox/app/backup/HostRestoreTransaction.java"
ADAPTER_TEST="backup/src/test/java/com/dshbox/app/backup/HostRestoreTransactionTest.java"
CRASH="backup/src/test/java/com/deepseekharness/app/backup/HostTransactionCrashProcess.java"

# ① **只有一套实现。** 手写的那份必须不存在 —— 保留两套正是硬约束 2 要避免的。
if [ -e "backup/src/main/java/com/dshbox/app/backup/HostRestoreCommit.java" ] \
   || [ -e "backup/src/test/java/com/dshbox/app/backup/HostRestoreCommitTest.java" ]; then
    fail "手写的 HostRestoreCommit 又出现了 —— 两套提交实现（硬约束 2）"
    CR_BAD=1
else
    ok "只有一套提交实现（手写的那份已删除，不保留两套）"
fi

# ② 上游那个类必须逐字节在场，并且它自己带 commit/recover。
if [ -f "$TXN" ] && grep -q 'public void commit(BackupControl control' "$TXN" \
   && grep -q 'public void recover()' "$TXN"; then
    ok "上游 HostDataTransaction 逐字节在场（自带 commit/recover）"
else
    fail "缺少 $TXN，或它没有 commit()/recover()"
    CR_BAD=1
fi

# ③ 上游那套比手写的严密在哪 —— 逐条钉住，否则「换了实现」可能换成了更弱的。
#    重验 before **和** after：手写那版只重验 before。
if grep -q 'CANDIDATE_CHANGED' "$TXN" && grep -q 'TARGET_CHANGED' "$TXN"; then
    ok "提交前重验 before **和** after（手写那版只验了 before）"
else
    fail "缺少 CANDIDATE_CHANGED/TARGET_CHANGED —— 候选在等待期间被改过也不会发现"
    CR_BAD=1
fi
#    嵌套目标：父目录与子目录互为目标时，两次 move 会互相踩。
if grep -q 'OVERLAPPING_TARGETS' "$TXN"; then
    ok "检测嵌套目标（父目录与子目录互为目标）"
else
    fail "缺少 OVERLAPPING_TARGETS —— 嵌套目标会让换入互相踩"
    CR_BAD=1
fi
#    五相位持久标记：崩溃恢复靠它 + 摘要双判，而不是只看摘要。
if grep -q 'mark("switching")' "$TXN" && grep -q 'mark("finalized")' "$TXN" \
   && grep -q 'has("rolling-back")' "$TXN"; then
    ok "五相位持久标记在场（verified/switching/finalized/rolling-back…）"
else
    fail "相位标记缺失 —— 崩溃恢复会失去一半判据"
    CR_BAD=1
fi
#    标记的写入方必须逐字节在场，而且它的内容要**自描述**。
if [ -f "backup/src/main/java/com/deepseekharness/app/backup/TransactionMarkers.java" ] \
   && grep -q 'directory.getName() + "\\n" + name' \
        backup/src/main/java/com/deepseekharness/app/backup/TransactionMarkers.java; then
    ok "相位标记的内容自描述（目录名+标记名，标记无法被悄悄挪用）"
else
    fail "TransactionMarkers 不在场，或它的内容不再自描述"
    CR_BAD=1
fi

# ④ **适配层自己加的那条检查：CROSS_DEVICE。** 上游没有它 ——
#    跨文件系统时 move 不报错、只是退化成拷贝，原子性**静默消失**。
if grep -q 'CROSS_DEVICE' "$ADAPTER" && grep -q 'device != taskDevice' "$ADAPTER"; then
    ok "跨文件系统的 move 在动手之前就被拒（上游没有这条，补在适配层）"
else
    fail "缺少 CROSS_DEVICE 前提检查 —— 跨设备时原子性会静默消失"
    CR_BAD=1
fi
#    而它必须在**动任何东西之前**跑：prepare 之前。
if grep -q 'public void checkSameFileSystem' "$ADAPTER" \
   && grep -q 'tx.checkSameFileSystem\|checkSameFileSystem(staged.groups)' "$ADAPTER_TEST"; then
    ok "CROSS_DEVICE 在 prepare 之前被调用（不是事后才发现）"
else
    fail "CROSS_DEVICE 没有在 prepare 之前被调用"
    CR_BAD=1
fi

# ⑤ **设置轴：非空必须被拒绝，不能静默空转。**
#    上游会在提交时一并切 settings.json 并有三处断言；恒空表的实现会让它们**永远通过** ——
#    那就是「没有输入空间的断言」（第 30 条）。
if grep -q 'SETTINGS_NOT_SUPPORTED' "$ADAPTER"; then
    ok "设置轴非空即拒（不让上游那三处设置断言变成恒真）"
else
    fail "设置轴静默空转 —— 上游的设置断言会永远通过（第 30 条）"
    CR_BAD=1
fi

# ⑥ 事务目录名必须是 UUID 形状（上游用它给事务命名，名字写进 plan.json 与每个标记）。
if grep -q 'TASK_NAME_NOT_UUID' "$ADAPTER" && grep -q 'Ids.uuid' "$ADAPTER"; then
    ok "事务目录名不是 UUID 形状时提前拦下（不靠上游抛 TRANSACTION_ID）"
else
    fail "没有提前校验事务目录名"
    CR_BAD=1
fi

# ⑦ **故障注入按命名边界，不按计数。**
#    手写那版数「第 N 次 move」，而 fs.atomic() 内部也在 move，于是注入打偏、
#    三个用例全绿却什么都没测到（第 31 条）。上游的 Fault.boundary(name) 由
#    被测代码自己给出边界名 —— 从根上不会有那个病。
if grep -q 'HostDataTransaction.Fault' "$ADAPTER" \
   && grep -q 'name.equals("new-dsh-sessions")' "$ADAPTER_TEST" \
   && grep -q 'name.startsWith("rollback-")' "$ADAPTER_TEST"; then
    ok "故障注入按上游的命名边界（不数第几次 move，第 31 条不会复发）"
else
    fail "故障注入退回了按位置计数 —— 注入会打偏而用例照绿"
    CR_BAD=1
fi
#    计数那套的痕迹必须一点都不剩。
if grep -q 'fault.swaps\|isSwap\|crashedMidSwap' "$ADAPTER_TEST"; then
    fail "测试里还留着按计数注入的痕迹 —— 两套注入混在一起会互相掩盖"
    CR_BAD=1
else
    ok "按计数注入的痕迹已清干净（只剩命名边界那套）"
fi

# ⑧ 崩溃现场必须由**真实代码路径**产生，不是测试里手搭。
#    上游那份测试用的是真杀子进程，比注入更强 —— 钉住它真的在。
if grep -q 'realProcessKillThenRecoveryAndKillDuringRollback' "$TXN_TEST" \
   && grep -q 'destroyForcibly' "$TXN_TEST" && [ -f "$CRASH" ]; then
    ok "崩溃测试真的杀子进程（destroyForcibly + 崩溃子进程辅助类在场）"
else
    fail "真杀进程那条用例或其辅助类不在了 —— 崩溃恢复失去最强的证据"
    CR_BAD=1
fi

# ⑨ 两个类各自的条数。只钉总数的话，一个可以静默消失而总数仍然够。
TXN_N=$(grep -c '@Test' "$TXN_TEST" 2>/dev/null || echo 0)
ADP_N=$(grep -c '@Test' "$ADAPTER_TEST" 2>/dev/null || echo 0)
if [ "$TXN_N" = "5" ]; then
    ok "上游事务测试 5 条 @Test 全在（一条都没删）"
else
    fail "HostDataTransactionTest 有 $TXN_N 条 @Test，期望 5"
    CR_BAD=1
fi
if [ "$ADP_N" = "7" ]; then
    ok "适配层集成测试 7 条 @Test"
else
    fail "HostRestoreTransactionTest 有 $ADP_N 条 @Test，期望 7"
    CR_BAD=1
fi

# ⑩ **新加的检查器必须自证会红。**
#    我给 check-dsha-imports.py 加了一条：同一路径不许同时出现在「已导入」与
#    not_imported 里。加之前实测有三条这样的自相矛盾记录（BackupTree /
#    NativeRestorePlan / HostDataTransaction），而**没有任何检查会红** ——
#    登记文件同时在说「拿了」和「没拿」，下一个读它的人会相信错的那一半（第 33 条）。
REG=governance/dsha-imports.json
REG_BAK="$(mktemp)"
cp "$REG" "$REG_BAK"
python3 - <<'PYREG'
import json
p = "governance/dsha-imports.json"
d = json.load(open(p, encoding="utf-8"))
d["not_imported"]["items"].append({
    "upstream_path": "app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java",
    "reason": "注入：把一条已在 files[] 里的路径塞进 not_imported",
})
json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PYREG
if python3 tools/check-dsha-imports.py >/dev/null 2>&1; then
    fail "not_imported 与 files/derived 重叠时检查器没有变红 —— 新检查是瞎的"
    CR_BAD=1
else
    ok "not_imported 与「已导入」重叠时确实变红（新检查有界）"
fi
cp "$REG_BAK" "$REG"
rm -f "$REG_BAK"
if python3 tools/check-dsha-imports.py >/dev/null 2>&1; then
    ok "复原后导入核验重新通过"
else
    fail "复原后导入核验仍未通过 —— 注入没被还原干净"
    CR_BAD=1
fi

# ⑩b **not_imported 的验证声明必须真跑，而且必须会红。**
#     第 33 条的成因就是一句没验证的「没搬」，而登记文件会**终止下一个人的怀疑**。
#     三种断言各注一次：抽掉 verify / 把 must_contain 的文本改成不存在 / 让 absent 的路径真的在。
VERIFY_INJ() {
    python3 - "$1" <<'PYV'
import json, sys
mode = sys.argv[1]
p = "governance/dsha-imports.json"
d = json.load(open(p, encoding="utf-8"))
items = d["not_imported"]["items"]
if mode == "drop":
    items[0].pop("verify", None)
elif mode == "contain":
    for e in items:
        v = e.get("verify") or {}
        if v.get("must_contain"):
            v["must_contain"][0]["text"] = "这个字符串在任何源码里都不存在-9f3a"
            break
elif mode == "absent":
    for e in items:
        v = e.get("verify") or {}
        if v.get("absent"):
            v["absent"].append("AGENTS.md")   # 这个路径当然在
            break
json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PYV
}
VERIFY_BAK="$(mktemp)"
cp governance/dsha-imports.json "$VERIFY_BAK"
VERIFY_BAD=0
for MODE in drop contain absent; do
    VERIFY_INJ "$MODE"
    if python3 tools/check-dsha-imports.py >/dev/null 2>&1; then
        fail "注入 $MODE 之后验证声明检查没有变红 —— 它对这个方向是瞎的"
        VERIFY_BAD=1
    fi
    cp "$VERIFY_BAK" governance/dsha-imports.json
done
if [ "$VERIFY_BAD" = "0" ]; then
    ok "not_imported 的验证声明三个方向都能变红（抽掉 verify / 内容不成立 / absent 落空）"
else
    CR_BAD=1
fi
cp "$VERIFY_BAK" governance/dsha-imports.json
rm -f "$VERIFY_BAK"
if python3 tools/check-dsha-imports.py >/dev/null 2>&1; then
    ok "复原后 not_imported 验证声明重新全部成立"
else
    fail "复原后导入核验仍未通过 —— 注入没被还原干净"
    CR_BAD=1
fi

# ⑪ **改了检查器，就必须证明它仍然有界**（第 32 条）。
# 我把 check-backup-contract.py 的「悬空限定名」规则从 `[A-Za-z0-9_.]\s*new` 改成
# `[A-Za-z0-9_]\s*\.\s*new`（旧的那个字符类会匹配 `return` 的最后一个字母，于是把
# **规则自己推荐的写法**判红）。改完两个方向都要验：
# ① 真的悬空限定名仍须变红；② 推荐写法必须不再变红。
DANGLE_PROBE="backup/src/main/java/com/dshbox/app/backup/DanglingProbe.java"
printf 'package com.dshbox.app.backup;\n\nimport java.io.*;\n\nfinal class DanglingProbe {\n  static String p(File f) throws IOException {\n    return java.nio.file.new String(Files.readAllBytes(f.toPath()));\n  }\n}\n' > "$DANGLE_PROBE"
if python3 tools/check-backup-contract.py >/dev/null 2>&1; then
    fail "注入的悬空限定名没有被判失败 —— 改完规则后它变瞎了"
    CR_BAD=1
else
    ok "注入的悬空限定名仍被判失败（改规则没有把这条检查弄瞎）"
fi
rm -f "$DANGLE_PROBE"
# ② 推荐写法（`return new String(Files.readAllBytes(…)`）必须通过 ——
#    这正是旧规则误伤的那一行，而它就在适配层的测试里。
if grep -q 'return new String(Files.readAllBytes(' "$ADAPTER_TEST" \
   && python3 tools/check-backup-contract.py >/dev/null 2>&1; then
    ok "推荐写法（new String(Files.readAllBytes(…))）不再被判失败（误伤已修）"
else
    fail "推荐写法被判失败，或契约核验未通过 —— 规则仍在误伤正确实现"
    CR_BAD=1
fi

section "5q. 自动备份的到期判定与保留策略（阶段 2.5）"

RET="$ROOT/backup/src/main/java/com/dshbox/app/backup/DshAutomaticBackupRetention.java"
RET_TEST="$ROOT/backup/src/test/java/com/dshbox/app/backup/DshAutomaticBackupRetentionTest.java"
POLICY="$ROOT/backup/src/main/java/com/deepseekharness/app/util/AutomaticBackupPolicy.java"
OWNER="$ROOT/backup/src/main/java/com/deepseekharness/app/util/ScheduledBackupOwner.java"

# ① 只有一份保留策略实现（不保留两套 —— 硬约束 2）
# ⚠️ 只数 **主源码**，而且要加括号 —— 第一版我写成 `find src -name A -o -name B`：
# 它把 `...RetentionTest.java` 也算进来了，于是「不唯一」红了，而实际只有一份实现。
# `-o` 不分组时还会与隐含的 `-print` 结合出反直觉的结果。
COUNT=$(find "$ROOT/backup/src/main" \( -name '*Retention*.java' -o -name '*Pruner*.java' \) 2>/dev/null | wc -l)
if [ "$COUNT" = "1" ] && [ -f "$RET" ]; then
    ok "保留策略只有一份实现（DshAutomaticBackupRetention），上游那份没有并存"
else
    fail "保留策略实现不唯一（找到 $COUNT 份）—— 两套实现会各自演化"
    CR_BAD=1
fi

# ② 两个逐字节导入的纯逻辑类在位（内容由 check-dsha-imports.py 逐字节核）
for f in "$POLICY" "$OWNER"; do
    if [ -f "$f" ]; then
        ok "已导入 $(basename "$f")（到期判定 / 单飞票据）"
    else
        fail "缺少 $(basename "$f") —— 2.5 的可复用部分没搬进来"
        CR_BAD=1
    fi
done

# ③ 比上游严的三条规则都在源码里（不是只在注释里说）
STRICT_BAD=0
for RULE in 'OPERATION.equals' 'COMPLETE.contains' 'Math.max(1, keep)' 'unverified'; do
    if ! grep -q -- "$RULE" "$RET"; then
        fail "保留策略缺少「$RULE」—— 比上游严的那三条没落实"
        STRICT_BAD=1
    fi
done
if [ "$STRICT_BAD" = "0" ]; then
    ok "比上游严的三条都在源码里：只碰 MAINTENANCE / 证明不了完整的不删 / keep 不允许为 0"
else
    CR_BAD=1
fi

# ④ **审计结论的可证伪形式必须在位。**
#    第 28 条：审计的结论也要能被推翻。没有这条用例，「不能直接抄上游 pruner」
#    就只是一句说法 —— 而它恰恰是我在 2.4b-② 栽过的同一个坑（第 33 条）。
if grep -q 'upstreamPrunerJudgementMatchesNothingOfOurs' "$RET_TEST"; then
    if grep -q 'assertEquals(2, r.expired.size())' "$RET_TEST"; then
        ok "审计结论有可证伪形式，且**同时**断言我们的策略确实会删（不是两边都不删的空断言）"
    else
        fail "审计实验只断言「上游不删」—— 那是个两边都绿、什么也没区分开的断言"
        CR_BAD=1
    fi
else
    fail "缺少 upstreamPrunerJudgementMatchesNothingOfOurs —— 审计结论无法被推翻"
    CR_BAD=1
fi

# ⑤ **四个方向各注一次**，证明每条规则都有自己的守卫。
#    只证明「删得对」不够 —— 要证明「删错了会红」。
#    ⚠️ 用 mktemp，不写死 /tmp（本文件上方已有过这个教训）。
RET_BAK="$(mktemp)"
cp "$RET" "$RET_BAK"
RET_INJ() {
    python3 - "$1" <<'PYR'
import pathlib, sys
m = sys.argv[1]
p = pathlib.Path("backup/src/main/java/com/dshbox/app/backup/DshAutomaticBackupRetention.java")
t = p.read_text(encoding="utf-8")
subs = {
 "operation": ("if (!OPERATION.equals(archive.operation)) continue;", "// 变异"),
 "integrity": ("      if (!COMPLETE.contains(archive.integrity)) {", "      if (false) {"),
 "keepzero":  ("int wanted = Math.max(1, keep);", "int wanted = keep;"),
 "tie":       (".thenComparing(Comparator.comparing((Archive a) -> a.name).reversed());",
               ".thenComparing(a -> a.name);"),
}
old, new = subs[m]
assert t.count(old) == 1, m
p.write_text(t.replace(old, new), encoding="utf-8")
PYR
}
RET_INJ_BAD=0
for MODE in operation integrity keepzero tie; do
    RET_INJ "$MODE"
    RUN=$(bash tools/run-backup-jvm-tests.sh 2>&1 | grep -oE 'Tests run: [0-9]+,  Failures: [0-9]+' | head -1)
    case "$RUN" in
        *"Failures: 1") : ;;
        *) fail "注入 $MODE 之后没有恰好 1 条红（实际：${RUN:-没跑到}）—— 这条规则的守卫不成立"
           RET_INJ_BAD=1 ;;
    esac
    cp "$RET_BAK" "$RET"
done
if [ "$RET_INJ_BAD" = "0" ]; then
    ok "保留策略四个方向都能变红（operation 过滤 / 完整性过滤 / keep 下限 / 平局次序）"
else
    CR_BAD=1
fi
cp "$RET_BAK" "$RET"
rm -f "$RET_BAK"

# ⑥ 「为什么没搬上游那几个 2.5 类」必须逐条带验证声明（第 33 条）。
#    这里只检查条数够不够 —— 声明本身成不成立由 check-dsha-imports.py 真跑。
if python3 tools/check-dsha-imports.py 2>&1 | grep -q 'not_imported 的 .* 条验证声明全部成立'; then
    ok "2.5 的「没搬」条目（AutomaticBackups / AutomaticBackupService / Pruner / VerifiedBackupCopy / VerifiedFilePublication）都带可执行验证声明"
else
    fail "2.5 的 not_imported 验证声明不成立"
    CR_BAD=1
fi

section "5r. 生产侧的 BackupFileSystem（阶段 2.6 的导出前置）"

# 为什么单独开一节：这个类是本项目**第一个**「本机编不了」的源码。
# 本机没有 android.jar，所以它不进 run-backup-jvm-tests.sh 的编译集 ——
# 那一步已经用一张声明表把这个排除做成显式的（多一个少一个都退 2）。
# 但「排除」只说明它**没被编译**，不说明它**是对的**。所以在这里补上
# 能本机判定的那部分：它用的是平台 API，不是原生库、也不是上游应用内部子系统。
ABS="$ROOT/backup/src/main/java/com/deepseekharness/app/backup/AndroidBackupFileSystem.java"
if [[ -f "$ABS" ]]; then
    # ① 正文逐字节（check-dsha-imports.py 已核）之外，钉住「用的是什么」。
    expect 0 "生产侧 BackupFileSystem 用平台 API（Os.lstat / O_NOFOLLOW / ParcelFileDescriptor）" \
        grep -q 'Os\.lstat' "$ABS"
    expect 0 "…且打开描述符时带 O_NOFOLLOW（不跟随符号链接）" \
        grep -q 'OsConstants\.O_NOFOLLOW' "$ABS"
    expect 0 "…且打开后**复查**类型/设备/inode（防 TOCTOU）" \
        grep -q 'SOURCE_CHANGED' "$ABS"

    # ② 反向：不许引入原生库或上游应用内部子系统。
    #    这一条与上面三条必须成对 —— 只查「有没有用对的 API」会允许
    #    「顺手 loadLibrary 一个别的库」（第 20 条补记的同族）。
    for bad in loadLibrary NativeStorage ConfigStore KeyVault CredentialRead HarnessController; do
        if grep -q "$bad" "$ABS"; then
            fail "AndroidBackupFileSystem 引入了 $bad —— 它应当是纯平台 API"
            CR_BAD=1
        else
            ok "AndroidBackupFileSystem 没有引入 $bad"
        fi
    done

    # ②b 导出编排在位，且**不重写容器格式** —— 它只允许调用上游那几个入口。
    EXP="$ROOT/backup/src/main/java/com/dshbox/app/backup/DshBackupExporter.java"
    if [[ -f "$EXP" ]]; then
        ok "导出编排 DshBackupExporter.java 在位"
        for wanted in 'HostSnapshot.create' 'FileBackupSource' 'fs.move'; do
            if grep -q "$wanted" "$EXP"; then
                ok "导出编排调用了上游的 $wanted"
            else
                fail "导出编排里找不到 $wanted —— 它可能在重写上游已有的东西"
                CR_BAD=1
            fi
        done
        # 反向：不许自己去**解析/构造那个容器**（魔数与长度前缀不该出现在这一层）。
        #
        # ⚠️ 这一条的第一版我把 `MessageDigest.getInstance("SHA-256")` 也列进了禁令 ——
        # 那会把这个类里**合法**的一处判红：它要给界面算**归档文件本身**的摘要。
        # 又一次是「规则比意图宽、把正确实现判失败」（第 32 条）。所以禁令只覆盖
        # 容器**格式**的记号；摘要算法的使用不在其中。
        for bad in 'DSHADATA' 'DataOutputStream' 'length-prefix'; do
            if grep -q "$bad" "$EXP"; then
                fail "导出编排里出现了 $bad —— 容器格式只应由上游的 BackupArchive 处理"
                CR_BAD=1
            else
                ok "导出编排没有自己碰容器格式（无 $bad）"
            fi
        done
    else
        fail "找不到导出编排 DshBackupExporter.java"
        CR_BAD=1
    fi

    # ②c 依赖边：阶段 2 的产出必须真的会被编进 APK。
    #     在 2.6 之前这条边不存在，而**没有任何检查会红** ——
    #     `settings.gradle.kts` 有 include(":backup") 就足以让 CI 单独编译并跑它的测试，
    #     于是「测试全绿」与「代码在 APK 里」是两件事（第 26 条的同族）。
    if grep -q 'implementation(project(":backup"))' "$ROOT/app/build.gradle.kts"; then
        ok "app 依赖 :backup（阶段 2 的代码真的会进 APK，DIV-0021）"
    else
        fail "app 没有依赖 :backup —— 阶段 2 的全部产出不会进入 APK，而测试仍然全绿"
        CR_BAD=1
    fi
    # 反向：不许**只有** include 而没有依赖边（这就是 2.6 之前的真实状态）。
    if grep -q 'include(":backup")' "$ROOT/settings.gradle.kts" &&
       ! grep -q 'project(":backup")' "$ROOT/app/build.gradle.kts"; then
        fail "settings 里有 include(\":backup\") 但没有任何模块依赖它 —— 「编译过了」不等于「进 APK 了」"
        CR_BAD=1
    else
        ok "include 与依赖边一致（不是「只编译不打包」）"
    fi

    # ③ 它必须真的被声明为「本机编不了」，而不是碰巧没被扫到。
    if grep -q '"AndroidBackupFileSystem.java"' "$ROOT/tools/run-backup-jvm-tests.sh"; then
        ok "本机编译的排除表里声明了 AndroidBackupFileSystem.java"
    else
        fail "本机编译的排除表里没有 AndroidBackupFileSystem.java —— 它会被静默地不编译"
        CR_BAD=1
    fi
else
    fail "找不到生产侧的 AndroidBackupFileSystem.java（导出路径的前置）"
    CR_BAD=1
fi

# ④ 注入：证明上面那条「排除表对照」真的会红。
#    形态是我真实担心的那个 —— 新增一个平台相关的源码而不更新声明表。
FAKE="$ROOT/backup/src/main/java/com/deepseekharness/app/backup/ZzFakePlatform.java"
printf 'package com.deepseekharness.app.backup;\nimport android.os.Build;\nclass ZzFakePlatform {}\n' > "$FAKE"
if bash "$ROOT/tools/run-backup-jvm-tests.sh" >/dev/null 2>&1; then
    fail "新增一个平台相关的源码之后，本机编译没有报「排除集合与声明不一致」"
    CR_BAD=1
else
    ok "新增平台相关源码而不更新声明表 → 本机编译退 2（排除不会静默扩大）"
fi
rm -f "$FAKE"
if bash "$ROOT/tools/run-backup-jvm-tests.sh" >/dev/null 2>&1; then
    ok "移除注入后本机编译重新通过"
else
    fail "移除注入后本机编译仍未通过 —— 注入没被还原干净"
    CR_BAD=1
fi

# ⑤ 注入：证明 check-dsha-imports.py 新加的「absent 父目录必须存在」会红。
IMP_BAK="$(mktemp)"
cp "$ROOT/governance/dsha-imports.json" "$IMP_BAK"
python3 - <<'PYI'
import json, pathlib
p = pathlib.Path("governance/dsha-imports.json")
d = json.loads(p.read_text(encoding="utf-8"))
for e in d["not_imported"]["items"]:
    if "+" in e["upstream_path"]:
        e["verify"]["absent"] = [
            "app/src/main/java/com/deepseekharness/app/backup/AndroidTreeFileSystem.java"]
        break
p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PYI
if python3 "$ROOT/tools/check-dsha-imports.py" >/dev/null 2>&1; then
    fail "absent 指向一个父目录都不存在的路径，导入核验没有变红 —— 那条断言什么都没证明"
    CR_BAD=1
else
    ok "absent 的父目录不存在 → 导入核验判失败（「因为错的原因通过」被挡住）"
fi

# ⑥ 注入：证明「组合路径不能把已导入的算进去」会红。
python3 - <<'PYI'
import json, pathlib
p = pathlib.Path("governance/dsha-imports.json")
d = json.loads(p.read_text(encoding="utf-8"))
for e in d["not_imported"]["items"]:
    if "+" in e["upstream_path"]:
        e["upstream_path"] = (
            "app/src/main/java/com/deepseekharness/app/backup/"
            "AndroidBackupFileSystem.java + AndroidTreeFileSystem.java + NativeStorage.java")
        break
p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PYI
if python3 "$ROOT/tools/check-dsha-imports.py" >/dev/null 2>&1; then
    fail "组合路径里塞了已导入的文件名，导入核验没有变红 —— 合成路径绕过了相交检查"
    CR_BAD=1
else
    ok "组合路径里含已导入的文件名 → 导入核验判失败（合成路径不再绕过相交检查）"
fi
cp "$IMP_BAK" "$ROOT/governance/dsha-imports.json"
rm -f "$IMP_BAK"
if python3 "$ROOT/tools/check-dsha-imports.py" >/dev/null 2>&1; then
    ok "复原后导入核验重新通过"
else
    fail "复原后导入核验仍未通过 —— 注入没被还原干净"
    CR_BAD=1
fi

section "5s. AGENTS.md 的指令预算（它被静默截断过三次）"

# 为什么这条必须存在：harness 对 `AGENTS.md` 有一个**硬字节上限 65536**，
# 超了不是报错，而是**静默截断尾部** —— 被截掉的正是最后那两节
# （「编码约定」与「已知陷阱」）。
# 我为此付出了三次：每次都是「加了一段很有用的话，本地全绿，而文件悄悄少了一节」。
# 所以把预算变成一条会红的断言，并且**正反两面都查**：
#   ① 不超预算；② 尾部两节都还在（截断的第一个症状就是它们消失）。
AGENTS="$ROOT/AGENTS.md"
if [ -f "$AGENTS" ]; then
    AG_BYTES=$(wc -c < "$AGENTS")
    if [ "$AG_BYTES" -le 65536 ]; then
        ok "AGENTS.md 在指令预算内（${AG_BYTES} / 65536 字节，余 $((65536 - AG_BYTES))）"
    else
        fail "AGENTS.md 超指令预算（${AG_BYTES} / 65536）—— 它会被静默截断尾部，请先压缩再提交"
        CR_BAD=1
    fi

    # ② 尾部两节必须在。这一条**独立于**上面的字节数：
    #    截断是按字节发生的，而「多少节被截掉」取决于文件当下的排版 ——
    #    只查字节数会允许「刚好卡在限额上、尾部已被啃掉」。
    for sec_name in "## 编码约定" "## 已知陷阱" "## 硬约束（改代码前必读）"; do
        if grep -q "^${sec_name}$" "$AGENTS"; then
            ok "AGENTS.md 的「${sec_name}」一节还在（没被截断）"
        else
            fail "AGENTS.md 里找不到「${sec_name}」—— 很可能被指令预算截断了"
            CR_BAD=1
        fi
    done

    # ③ 注入：证明 ① 真的会红。
    #    形态就是真实发生过的 —— 把文件撑过 65536。
    AG_BAK="$(mktemp)"
    cp "$AGENTS" "$AG_BAK"
    python3 -c "
import pathlib
p = pathlib.Path('$AGENTS')
p.write_text(p.read_text(encoding='utf-8') + '\n' + '填充' * 900 + '\n', encoding='utf-8')
"
    AG_INJ=$(wc -c < "$AGENTS")
    if [ "$AG_INJ" -le 65536 ]; then
        fail "注入没把 AGENTS.md 撑过预算（只有 ${AG_INJ} 字节）—— 这条注入本身是无效的"
        CR_BAD=1
    else
        ok "注入把 AGENTS.md 撑到 ${AG_INJ} 字节（超过 65536）—— ① 的目标状态可达"
    fi
    cp "$AG_BAK" "$AGENTS"
    rm -f "$AG_BAK"
    if [ "$(wc -c < "$AGENTS")" -le 65536 ]; then
        ok "复原后 AGENTS.md 重新回到预算内"
    else
        fail "复原后 AGENTS.md 仍在预算外 —— 注入没被还原干净"
        CR_BAD=1
    fi
else
    fail "找不到 AGENTS.md"
    CR_BAD=1
fi

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
        printf '  [SKIP] %-52s 无审计副本\n' "上游工作树检查: $(basename "$d")"
        SKIP=$((SKIP + 1))
        RESULTS+=("SKIP|上游 $(basename "$d") 未被修改|无审计副本，未检查")
    fi
done

section "7. 脚本可执行位（读 git 索引，不看工作区）"

# 为什么必须单独查这一项：
# 本套件通过 run_tool() 用解释器显式调用脚本，因此永远测不到可执行位；
# 而 CI 会检查 `-x`。于是会出现「本地全绿、CI 失败」——
# 2026-10-13 首次推送就撞上了这个盲区（tools/make-anchor-fixture.sh 是 100644）。
#
# 逻辑放在 tools/check-exec-bits.py 里而不是内联在这里，是因为它曾经有两份实现：
# 套件一份、contracts.yml 一份。阶段 1 我只改了套件那份，CI 立刻红 ——
# 两份实现必然漂移，所以只留一份。
if git rev-parse --git-dir >/dev/null 2>&1; then
    EB_OUT="$(python3 tools/check-exec-bits.py 2>&1 || true)"
    EB_N="$(printf '%s' "$EB_OUT" | grep -oE '检查了 [0-9]+ 个' | grep -oE '[0-9]+' || echo 0)"
    if expect 0 "索引中 .sh 模式与继承基线一致（$EB_N 个）" \
            python3 tools/check-exec-bits.py; then :; fi
else
    printf '  [SKIP] %-52s 非 git 检出\n' "索引中 .sh 可执行位"
    SKIP=$((SKIP + 1))
    RESULTS+=("SKIP|索引中 .sh 可执行位|非 git 检出，未检查")
fi

# 这一节同样要证明自己会失败，否则「全绿」可能只是因为检查根本没在查。
# 两个方向都测：
#   方向 A（本项目脚本被改成 100644）—— 拦住我们自己的疏漏
#   方向 B（继承文件被改成 100755）—— 拦住基线失真，
#          单方向检查做不到这一点：只查「非可执行就报错」的话，
#          基线里的文件被 chmod 成可执行反而放行，真实状态已与上游不一致。
if git rev-parse --git-dir >/dev/null 2>&1; then
    EB_PROBE="$(git ls-files 'tools/*.sh' | head -1)"
    EB_INH="$(python3 -c '
import json
try:
    d=json.load(open("governance/inherited-lint-baseline.json",encoding="utf-8"))
    v=d.get("exec_bit",{}).get("inherited_non_executable",[])
    print(v[0] if v else "")
except Exception:
    print("")
' 2>/dev/null || echo "")"

    if [ -n "$EB_PROBE" ]; then
        git update-index --chmod=-x "$EB_PROBE" 2>/dev/null || true
        expect 1 "可执行位检查会拦下本项目脚本被改为 100644" \
            python3 tools/check-exec-bits.py
        git update-index --chmod=+x "$EB_PROBE" 2>/dev/null || true
    fi

    if [ -n "$EB_INH" ]; then
        git update-index --chmod=+x "$EB_INH" 2>/dev/null || true
        expect 1 "可执行位检查会拦下继承文件被改为 100755（基线失真）" \
            python3 tools/check-exec-bits.py
        git update-index --chmod=-x "$EB_INH" 2>/dev/null || true
    fi

    expect 0 "恢复后重新通过" python3 tools/check-exec-bits.py
fi

section "汇总"
TOTAL=$((PASS + FAIL + SKIP))
printf '  通过: %d\n' "$PASS"
printf '  失败: %d\n' "$FAIL"
printf '  跳过: %d\n' "$SKIP"
printf '  合计: %d 项\n' "$TOTAL"

if [ "$SKIP" -gt 0 ]; then
    printf '\n  ⚠️  跳过项（既不算通过，也不算失败）：\n'
    for r in "${RESULTS[@]}"; do
        case "$r" in
            SKIP*)
                body="${r#SKIP|}"
                printf '    - %s（%s）\n' "${body%%|*}" "${body##*|}"
                ;;
        esac
    done
fi

if [ "$FAIL" -gt 0 ]; then
    printf '\n  失败项：\n'
    for r in "${RESULTS[@]}"; do
        case "$r" in FAIL*) printf '    - %s\n' "${r#FAIL|}" ;; esac
    done
    printf '\n  存在失败项，退出码 1。\n'
    exit 1
fi

printf '\n  %d 项通过、0 项失败、%d 项跳过。\n' "$PASS" "$SKIP"
if [ "$SKIP" -gt 0 ]; then
    printf '  注意：有跳过项，本次运行并未验证全部 %d 项。\n' "$TOTAL"
fi
exit 0
