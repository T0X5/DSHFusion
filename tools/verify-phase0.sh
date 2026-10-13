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
    run_tool tools/check-divergence-anchors.py --upstream /tmp/definitely-not-here

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

        # 偏离重放此时应报「4 项失败、1 项已生效」—— 这是设计如此，不是缺陷
        IMP_DIV="$(cd "$IMP_CLONE" && bash tools/apply-divergences.sh --check 2>&1 || true)"
        if printf '%s' "$IMP_DIV" | grep -q "4 项失败" &&
           printf '%s' "$IMP_DIV" | grep -q "DIV-0005"; then
            printf '  [PASS] %-52s\n' "导入后偏离重放正确报出 4 项待处理"
            PASS=$((PASS + 1)); RESULTS+=("PASS|导入后偏离重放|4 项失败 1 项已生效")
        else
            printf '  [FAIL] %-52s\n' "导入后偏离重放未报出预期的 4 项失败"
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

section "5. 静态检查"

expect 0 "所有 shell 脚本语法正确（bash -n）" \
    bash -c 'for f in tools/*.sh; do bash -n "$f" || exit 1; done'
expect 0 "所有 Python 工具可编译" \
    python3 -m compileall -q tools
expect 0 "所有 JSON 可解析" \
    bash -c 'for f in $(find . -name "*.json" -not -path "./.git/*"); do python3 -c "import json,sys; json.load(open(sys.argv[1],encoding=\"utf-8\"))" "$f" || exit 1; done'
expect 0 "受保护许可原件哈希与登记一致" \
    run_tool tools/check-license-hashes.py

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

ACTIONLINT=""
for candidate in actionlint /tmp/actionlint/actionlint /usr/local/bin/actionlint; do
    if command -v "$candidate" >/dev/null 2>&1; then ACTIONLINT="$candidate"; break; fi
done
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
# 本套件通过 run_tool() 用解释器显式调用脚本（见第 42 行），因此永远测不到
# 可执行位。CI 的 contracts.yml 却会 `find tools -name '*.sh'` 后检查 `-x`，
# 于是本地全绿、CI 失败。2026-10-13 首次推送就撞上了这个盲区
# （tools/make-anchor-fixture.sh 索引模式为 100644）。
#
# 读 git 索引而不是工作区权限：索引里记录的模式才是别人 clone 后拿到的模式。
# 工作区权限可以被本地 chmod 掩盖，索引不会。
#
# 阶段 1 起 tools/ 里有上游原样导入的脚本。上游 DSHBox 自己的索引里就有
# 13 个 .sh 是 100644（16 个是 100755）—— 这不是导入丢了权限，用
# `git -C <上游> ls-files -s '*.sh'` 可复核。因此判定改成双向对照基线：
#   不在基线的 .sh 必须 100755（拦住本项目自己的疏漏）
#   在基线的 .sh 必须 100644（拦住基线失真、也拦住有人悄悄改动继承状态）
if git rev-parse --git-dir >/dev/null 2>&1; then
    EB_BAD=""
    EB_NONEXEC="$(python3 -c '
import json,sys
try:
    d=json.load(open("governance/inherited-lint-baseline.json",encoding="utf-8"))
    print("\n".join(d.get("exec_bit",{}).get("inherited_non_executable",[])))
except Exception:
    pass
' 2>/dev/null || true)"

    while IFS= read -r line; do
        mode="${line%% *}"
        path="${line##*$'\t'}"
        case "$path" in
            *.sh) ;;
            *) continue ;;
        esac
        if printf '%s\n' "$EB_NONEXEC" | grep -qxF "$path"; then
            want="100644"   # 继承自上游，保持与上游一致
        else
            want="100755"
        fi
        [ "$mode" = "$want" ] || EB_BAD="${EB_BAD}${path}(实际${mode}/应为${want}) "
    done < <(git ls-files -s)

    EB_N="$(printf '%s\n' "$EB_NONEXEC" | grep -c . || true)"
    if [ -z "$EB_BAD" ]; then
        printf '  [PASS] %-52s 全部符合（%s 个继承 100644）\n' \
            "索引中 .sh 模式与基线一致" "$EB_N"
        PASS=$((PASS + 1))
        RESULTS+=("PASS|索引中 .sh 模式与基线一致|$EB_N 个继承 100644")
    else
        printf '  [FAIL] %-52s %s\n' "索引中 .sh 模式与基线不符" "$EB_BAD"
        FAIL=$((FAIL + 1))
        RESULTS+=("FAIL|索引中 .sh 模式与基线不符|$EB_BAD")
    fi
else
    printf '  [SKIP] %-52s 非 git 检出\n' "索引中 .sh 可执行位"
    SKIP=$((SKIP + 1))
    RESULTS+=("SKIP|索引中 .sh 可执行位|非 git 检出，未检查")
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
