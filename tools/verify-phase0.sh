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

section "4d. 阶段 1 导入器 import-upstream-base.py"

if [ -d ../audit/DSHBox ]; then
    IMP_UP="$(cd ../audit/DSHBox && pwd)"

    # 正路径 1：在 main 上必须拒绝（强制走分支 + PR）。
    # 必须在专门的 main 克隆里测 —— 套件自身可能就跑在 phase-1 分支上。
    IMP_MAIN="$(mktemp -d)"
    rm -rf "$IMP_MAIN"
    if git clone -q . "$IMP_MAIN" 2>/dev/null &&
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
    if git clone -q . "$IMP_CLONE" 2>/dev/null &&
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

        # 偏离重放此时应报「待处理项全部失败、排除项已生效」。设计如此，不是缺陷。
        #
        # 断言不硬编码数量：早先写死「4 项失败」，新增 DIV-0006 后立刻失效，
        # 而失效的原因是测试过期、不是代码出错 —— 这种误报会训练人忽略红灯。
        # 改为从治理文件推导：凡是作为「导入排除」登记的偏离（见
        # import-exclusions.json 的 excluded_paths[].divergence），导入后天然已生效；
        # 其余都需要人工施加，导入后应全部报失败。新增偏离时测试自动跟上。
        IMP_DIV="$(cd "$IMP_CLONE" && bash tools/apply-divergences.sh --check 2>&1 || true)"
        EXP_FAIL="$(cd "$IMP_CLONE" && python3 -c "
import json
d=json.load(open('governance/divergences.json',encoding='utf-8'))
x=json.load(open('governance/import-exclusions.json',encoding='utf-8'))
excl={e.get('divergence') for e in x.get('excluded_paths',[])}
print(sum(1 for e in d['planned'] if e['id'] not in excl))
" 2>/dev/null || echo 5)"
        if printf '%s' "$IMP_DIV" | grep -q "$EXP_FAIL 项失败" &&
           printf '%s' "$IMP_DIV" | grep -q "DIV-0005"; then
            printf '  [PASS] %-52s\n' "导入后偏离重放正确报出 $EXP_FAIL 项待处理"
            PASS=$((PASS + 1)); RESULTS+=("PASS|导入后偏离重放|$EXP_FAIL 项失败 1 项已生效")
        else
            printf '  [FAIL] %-52s\n' "导入后偏离重放未报出预期的 $EXP_FAIL 项失败"
            FAIL=$((FAIL + 1)); RESULTS+=("FAIL|导入后偏离重放|未报出预期失败项")
        fi
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

for scen in upstream-id upstream-ver wrong-ns wrong-label bad-authority; do
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
