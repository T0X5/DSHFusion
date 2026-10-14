#!/usr/bin/env bash
# DSHFusion 上游同步工具（阶段 0）。
#
# 职责：拉取上游 → 生成变更摘要 → 冲突预警。不自动改主干、不自动合并、不自动推送。
# 合并与开 PR 由人决定（见 docs/UPSTREAM-SYNC.md）。
#
# 用法：
#   tools/sync-upstream.sh dshbox --fetch      # 拉取上游镜像并生成摘要
#   tools/sync-upstream.sh dsha   --fetch
#   tools/sync-upstream.sh dshbox --report     # 只用本地镜像生成摘要
#   tools/sync-upstream.sh dshbox --record <commit>   # 更新 upstreams.json 的锁定提交
#
# --record 会先做 4 项校验，任一不通过即拒绝更新（退出码 1）：
#   1. 提交对象在镜像中存在且是 commit；
#   2. 提交是该上游默认分支的祖先（证明来源正确）；
#   3. 当前锁定提交是它的祖先（证明关系是向前推进而非回退）；
#   4. docs/upstream-reports/<日期>-<上游>.md 里有针对该提交的审核记录，
#      且 integration 为 passed 或 not-applicable。
# 理由：提前推进 locked_commit 会让下次摘要的范围从新提交开始算，
# 未集成的提交就此静默消失。
#
# 环境：
#   DSHFUSION_MIRRORS  上游镜像目录（默认 ../upstream-mirrors）

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MIRRORS="${DSHFUSION_MIRRORS:-$(cd "$ROOT/.." && pwd)/upstream-mirrors}"
REPORTS="$ROOT/docs/upstream-reports"
DIVERGENCES_JSON="$ROOT/governance/divergences.json"

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "==> $*"; }

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

[ $# -ge 1 ] || usage 1
UPSTREAM_ID="$1"; shift

case "$UPSTREAM_ID" in
    dshbox|dsha) ;;
    -h|--help) usage 0 ;;
    *) die "未知上游 '$UPSTREAM_ID'（可选：dshbox / dsha）" ;;
esac

ACTION=""
RECORD_COMMIT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --fetch)  ACTION="fetch"; shift ;;
        --report) ACTION="report"; shift ;;
        --record) ACTION="record"; RECORD_COMMIT="${2:-}"; [ -n "$RECORD_COMMIT" ] || die "--record 需要一个提交号"; shift 2 ;;
        -h|--help) usage 0 ;;
        *) die "未知参数 '$1'" ;;
    esac
done
[ -n "$ACTION" ] || usage 1

# ---------- 读取上游清单 ----------
command -v python3 >/dev/null 2>&1 || die "需要 python3"

read_upstream() {   # $1 = 字段名
    python3 - "$UPSTREAM_ID" "$1" <<'PY'
import json, sys
from pathlib import Path
doc = json.loads(Path("governance/upstreams.json").read_text(encoding="utf-8"))
entry = next((u for u in doc["upstreams"] if u["id"] == sys.argv[1]), None)
if entry is None:
    raise SystemExit("UPSTREAM_NOT_FOUND")
print(entry.get(sys.argv[2], ""))
PY
}

FULL_NAME="$(read_upstream full_name)"
LOCKED="$(read_upstream locked_commit)"
URL="$(read_upstream url)"
echo "上游：$FULL_NAME"
echo "锁定：$LOCKED"

# ---------- record ----------
# 这是唯一会改动 governance/upstreams.json 的动作，因此必须先证明「这次同步确实
# 已经完成并通过验证」。校验不通过时拒绝更新，因为提前把 locked_commit 推到新提交
# 会让下一次摘要的范围从新提交开始算 —— 未集成的提交就此静默消失。
if [ "$ACTION" = "record" ]; then
    [ ${#RECORD_COMMIT} -eq 40 ] || die "--record 需要完整的 40 位提交号（收到 ${#RECORD_COMMIT} 位）"
    case "$RECORD_COMMIT" in
        *[!0-9a-fA-F]*) die "--record 不是合法的十六进制提交号：$RECORD_COMMIT" ;;
    esac

    # 镜像必须在场：没有镜像就无法验证提交来源
    case "$UPSTREAM_ID" in
        dshbox) RECORD_MIRROR="$MIRRORS/DSHBox.git" ;;
        dsha)   RECORD_MIRROR="$MIRRORS/DSHA.git" ;;
    esac
    [ -d "$RECORD_MIRROR" ] || die "找不到上游镜像 $RECORD_MIRROR，无法验证提交来源。
   先建立镜像：git clone --mirror \"$URL\" \"$RECORD_MIRROR\""

    # --- 校验 1：提交对象真实存在，且确实是 commit ---
    if ! git -C "$RECORD_MIRROR" cat-file -e "$RECORD_COMMIT^{commit}" 2>/dev/null; then
        die "校验失败：$RECORD_COMMIT 在上游镜像 $UPSTREAM_ID 中不存在或不是 commit 对象。
   拒绝把不存在于该上游的提交写进 locked_commit。"
    fi
    info "校验 1/4 通过：提交对象存在于 $UPSTREAM_ID 镜像"

    # --- 校验 2：提交确实来自该上游的默认分支（而非任意游离对象） ---
    RECORD_BRANCH="$(read_upstream default_branch)"
    [ -n "$RECORD_BRANCH" ] || RECORD_BRANCH="main"
    if ! git -C "$RECORD_MIRROR" merge-base --is-ancestor "$RECORD_COMMIT" "refs/heads/$RECORD_BRANCH" 2>/dev/null; then
        die "校验失败：$RECORD_COMMIT 不是 $UPSTREAM_ID 的 refs/heads/$RECORD_BRANCH 的祖先。
   可能是别的分支、别的上游仓库、或已被 force-push 丢弃的提交。
   当前该分支 HEAD：$(git -C "$RECORD_MIRROR" rev-parse "refs/heads/$RECORD_BRANCH" 2>/dev/null || echo '未知')"
    fi
    info "校验 2/4 通过：提交属于 $UPSTREAM_ID 的 $RECORD_BRANCH 分支历史"

    # --- 校验 3：提交关系正确（不倒退、不回退、确为新提交） ---
    if [ "$LOCKED" = "$RECORD_COMMIT" ]; then
        die "校验失败：$RECORD_COMMIT 已经是当前锁定提交，无需更新。"
    fi
    if ! git -C "$RECORD_MIRROR" cat-file -e "$LOCKED^{commit}" 2>/dev/null; then
        die "校验失败：当前锁定提交 $LOCKED 不在镜像中，无法验证新旧关系。
   先修复 upstreams.json 或重新建立镜像。"
    fi
    if ! git -C "$RECORD_MIRROR" merge-base --is-ancestor "$LOCKED" "$RECORD_COMMIT" 2>/dev/null; then
        die "校验失败：当前锁定提交 $LOCKED 不是 $RECORD_COMMIT 的祖先。
   这说明新提交不是从锁定提交线性推进而来（可能上游 force-push、或选错了提交）。
   直接更新会让 $LOCKED..$RECORD_COMMIT 之间的历史关系不可信。
   本工具**不提供**强制跳过的开关 —— 请人工确认上游历史后，
   手动编辑 governance/upstreams.json 并写明理由（手动编辑会留下 diff 痕迹）。"
    fi
    RECORD_AHEAD="$(git -C "$RECORD_MIRROR" rev-list --count "$LOCKED..$RECORD_COMMIT")"
    info "校验 3/4 通过：锁定提交是它的祖先，领先 $RECORD_AHEAD 个提交"

    # --- 校验 4：存在对应的审核记录，且已声明集成通过 ---
    # 不按文件名推断记录归属：摘要文件名用「运行日期」，而提交日期可能不同
    # （从旧提交开始集成、跨天合并都会错位）。改为在全部摘要里按 record_commit
    # 内容查找 —— 记录属于哪个提交由内容决定，不由文件名决定。
    RECORD_FILE=""
    if [ -d "$REPORTS" ]; then
        RECORD_FILE="$(grep -rl --include='*.md' -F "record_commit: $RECORD_COMMIT" "$REPORTS" 2>/dev/null | head -1 || true)"
    fi

    if [ -z "$RECORD_FILE" ]; then
        cat >&2 <<EOF
校验失败：在 $REPORTS 下找不到针对 $RECORD_COMMIT 的审核记录。

在对应的摘要文件末尾补充以下段落（字段名不可改）：

    ## 审核记录

    \`\`\`yaml
    record_commit: $RECORD_COMMIT
    upstream: $UPSTREAM_ID
    range: $LOCKED..$RECORD_COMMIT
    integration: passed
    tests: <实际执行的命令与结果>
    divergences_replayed: <重放了几处偏离>
    reviewer: <你的名字>
    date: $(date -Iseconds)
    \`\`\`

integration 必须是 passed / not-applicable / rejected 三者之一。
未写审核记录时拒绝更新 locked_commit —— 否则未集成的提交会在下次摘要里消失。

当前已有的摘要文件：
$(ls -1 "$REPORTS"/*.md 2>/dev/null | sed 's/^/    /' || echo "    （无）")
EOF
        exit 1
    fi

    RECORD_INTEGRATION="$(python3 - "$RECORD_FILE" <<'PY'
import re, sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(encoding="utf-8")
m = re.search(r"^integration:\s*(\S+)\s*$", text, re.M)
print(m.group(1) if m else "")
PY
)"
    case "$RECORD_INTEGRATION" in
        passed|not-applicable)
            info "校验 4/4 通过：审核记录 $(basename "$RECORD_FILE")，integration=$RECORD_INTEGRATION"
            ;;
        "")
            die "校验失败：$RECORD_FILE 提到了该提交，但缺少 integration 字段。"
            ;;
        *)
            die "校验失败：$RECORD_FILE 的 integration=$RECORD_INTEGRATION，不是 passed 或 not-applicable。
   集成未通过时不得推进 locked_commit —— 保持稳定基线不动。"
            ;;
    esac

    # 记录里声明的范围必须与实际一致，防止记录与提交错配
    RECORD_RANGE="$(python3 - "$RECORD_FILE" <<'PY'
import re, sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(encoding="utf-8")
m = re.search(r"^range:\s*(\S+)\s*$", text, re.M)
print(m.group(1) if m else "")
PY
)"
    if [ -n "$RECORD_RANGE" ] && [ "$RECORD_RANGE" != "$LOCKED..$RECORD_COMMIT" ]; then
        die "校验失败：审核记录声明的范围是 $RECORD_RANGE，
   但按当前 locked_commit 计算应为 $LOCKED..$RECORD_COMMIT。
   记录与提交错配，拒绝更新。"
    fi

    python3 - "$UPSTREAM_ID" "$RECORD_COMMIT" "$RECORD_FILE" "$RECORD_AHEAD" <<'PY'
import json, sys, datetime
from pathlib import Path
path = Path("governance/upstreams.json")
doc = json.loads(path.read_text(encoding="utf-8"))
uid, commit, report, ahead = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
entry = next((u for u in doc["upstreams"] if u["id"] == uid), None)
if entry is None:
    raise SystemExit("UPSTREAM_NOT_FOUND")
old = entry.get("locked_commit")
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
entry["locked_commit"] = commit
entry["locked_commit_date"] = now
# 留下审计痕迹：谁在什么时候、依据哪份审核记录推进了锁定提交
history = entry.setdefault("locked_commit_history", [])
history.append({
    "commit": commit,
    "previous": old,
    "recorded_at": now,
    "commits_integrated": int(ahead),
    "review_record": report,
})
path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"{uid}: {old} -> {commit}（集成 {ahead} 个提交）")
PY
    info "已更新 upstreams.json，并在 locked_commit_history 留下审计痕迹。"
    exit 0
fi

# ---------- 定位镜像 ----------
case "$UPSTREAM_ID" in
    dshbox) MIRROR="$MIRRORS/DSHBox.git" ;;
    dsha)   MIRROR="$MIRRORS/DSHA.git" ;;
esac

if [ ! -d "$MIRROR" ]; then
    cat >&2 <<EOF
ERROR: 找不到上游镜像 $MIRROR

仓库不跟踪镜像（避免把上百 MB 对象库提交进 git）。请先建立：

    mkdir -p "$MIRRORS"
    git clone --mirror "$URL" "$MIRROR"

或用 DSHFUSION_MIRRORS 指定其他位置。
EOF
    exit 1
fi

if [ "$ACTION" = "fetch" ]; then
    info "拉取上游更新到镜像"
    git -C "$MIRROR" fetch --prune origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
fi

# ---------- 确定目标提交 ----------
DEFAULT_BRANCH="$(read_upstream default_branch)"
[ -n "$DEFAULT_BRANCH" ] || DEFAULT_BRANCH="main"
TARGET="$(git -C "$MIRROR" rev-parse "refs/heads/$DEFAULT_BRANCH" 2>/dev/null || true)"
[ -n "$TARGET" ] || die "镜像里找不到分支 $DEFAULT_BRANCH（镜像可能未正确 fetch）"

if [ "$LOCKED" = "$TARGET" ]; then
    info "上游没有新提交（$TARGET 就是当前锁定提交）。"
    exit 0
fi

git -C "$MIRROR" cat-file -e "$LOCKED^{commit}" 2>/dev/null \
    || die "锁定提交 $LOCKED 不在镜像中，无法计算变更范围。请检查 upstreams.json。"

info "新提交范围：$LOCKED..$TARGET"

# ---------- 生成摘要 ----------
mkdir -p "$REPORTS"
DATE="$(date +%Y%m%d)"
REPORT="$REPORTS/${DATE}-${UPSTREAM_ID}.md"

# 变更文件清单（供后续交集计算）
CHANGED="$(git -C "$MIRROR" diff --name-only "$LOCKED" "$TARGET")"

{
    echo "# 上游同步摘要：$FULL_NAME"
    echo
    echo "- 生成时间：$(date -Iseconds)"
    echo "- 上游：\`$FULL_NAME\`"
    echo "- 范围：\`$LOCKED\` → \`$TARGET\`"
    echo "- 目标分支：\`$DEFAULT_BRANCH\`"
    echo "- 提交数：$(git -C "$MIRROR" rev-list --count "$LOCKED..$TARGET")"
    echo "- 变更文件数：$(printf '%s\n' "$CHANGED" | grep -c . || true)"
    echo

    echo "## 1. 提交清单"
    echo
    echo "| 提交 | 日期 | 作者 | 标题 | 标记 |"
    echo "|---|---|---|---|---|"
    git -C "$MIRROR" log --reverse --date=short \
        --format='%h%x09%ad%x09%an%x09%s' "$LOCKED..$TARGET" | while IFS=$'\t' read -r sha date author subject; do
        mark=""
        case "$subject" in
            *fix*|*FIX*) mark="fix" ;;
        esac
        case "$subject" in
            *security*|*Security*|*CVE*|*安全*|*漏洞*) mark="${mark:+$mark,}security" ;;
        esac
        echo "| \`$sha\` | $date | $author | ${subject//|/\\|} | $mark |"
    done
    echo

    echo "## 2. 按模块聚合的变更量"
    echo
    echo "| 模块 | 文件数 |"
    echo "|---|---:|"
    printf '%s\n' "$CHANGED" | grep . | awk -F/ '{print ($1=="app"||$1=="docs"||$1=="tools"||$1=="website")?$1:$1}' \
        | sort | uniq -c | sort -rn | head -25 \
        | while read -r count module; do echo "| \`$module\` | $count |"; done
    echo

    echo "## 3. ⚠️ 冲突预警（变更文件 ∩ 已登记偏离）"
    echo
    if [ -f "$DIVERGENCES_JSON" ]; then
        # 变更清单经临时文件传给 Python。
        # 不能走管道：`python3 - <<'PY'` 里的 heredoc 会**覆盖**管道输入
        # （shellcheck SC2259），Python 读到的是脚本自身，清单等于没传 ——
        # 这不是风格问题，是我改这里时真引入过的功能 bug，被 CI 的
        # 静态检查门禁拦住。用 mktemp 而不是写死文件名：并发同步不会
        # 互相覆盖，也不在源码里留下会被隐私审计报出的本机路径。
        # 提醒：本文件里注释行不要以「井号 + shellcheck」开头，
        # 那会被当成 lint 指令解析并报「无法解析的指令」。
        CHANGED_LIST="$(mktemp)"
        trap 'rm -f "$CHANGED_LIST"' EXIT
        printf '%s\n' "$CHANGED" | grep . > "$CHANGED_LIST" || true
        python3 - "$DIVERGENCES_JSON" "$CHANGED_LIST" <<'PY' || true
import json, sys
from pathlib import Path
changed = set(Path(sys.argv[2]).read_text(encoding="utf-8").split())
doc = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
hits = []
for state in ("applied", "planned"):
    for entry in doc.get(state, []):
        for target in entry.get("target_files", []):
            if target in changed or any(c.startswith(target.rstrip("/") + "/") for c in changed):
                hits.append((entry["id"], entry.get("title", ""), target, state))
if not hits:
    print("本次上游变更**没有**触及已登记偏离的文件。")
else:
    print("| 偏离 | 标题 | 文件 | 状态 |")
    print("|---|---|---|---|")
    for div_id, title, target, state in hits:
        print(f"| `{div_id}` | {title} | `{target}` | {state} |")
    print()
    print(f"**共 {len(hits)} 处冲突需要人工重放。** 合并后必须跑 `tools/apply-divergences.sh`。")
PY
    else
        echo "（divergences.json 不存在，跳过）"
    fi
    echo

    echo "## 4. 测试基线变化"
    echo
    BEFORE=$(git -C "$MIRROR" ls-tree -r --name-only "$LOCKED" | grep -cE 'src/(test|androidTest)/.*\.(kt|java)$' || true)
    AFTER=$(git -C "$MIRROR" ls-tree -r --name-only "$TARGET" | grep -cE 'src/(test|androidTest)/.*\.(kt|java)$' || true)
    echo "- 上游测试**文件**数：$BEFORE → $AFTER（仅供参考，文件数会被重构轻易改变）"
    if [ "$AFTER" -lt "$BEFORE" ]; then
        echo "- 🚨 **测试文件净减少 $((BEFORE - AFTER)) 个。** 必须查明是删测试还是重构，不允许静默接受。"
    fi
    echo "- 本项目护栏是 **\`@Test\` 方法数 ≥ 1058**，不是文件数。"
    echo "  合并后必须实际统计并对比（见 docs/FUSION-PLAN.md 阶段 1 验收清单第 8 项）。"
    echo

    echo "## 5. ⚠️ 许可与依赖变化（要求人工确认）"
    echo
    for f in LICENSE THIRD_PARTY_NOTICES.md TRADEMARK.md gradle/libs.versions.toml \
             app/build.gradle.kts app/build.gradle package.json package-lock.json \
             runtime-bundle/bundle.yaml .github/workflows/android.yml; do
        if printf '%s\n' "$CHANGED" | grep -qx "$f"; then
            echo "- \`$f\` **已变更** — 需人工确认许可/依赖影响"
        fi
    done
    echo
    echo "> 若 \`LICENSE\`、\`THIRD_PARTY_NOTICES.md\` 或 \`TRADEMARK.md\` 变更，"
    echo "> 必须重新生成 \`governance/upstream-license-hashes.json\` 并复核 \`governance/ATTRIBUTION.md\`。"
    echo

    echo "## 6. 变更文件全清单"
    echo
    echo '```'
    printf '%s\n' "$CHANGED"
    echo '```'
    echo

    echo "## 7. 下一步（人工）"
    echo
    echo '```bash'
    echo "git switch -c sync/$UPSTREAM_ID-$(printf '%s' "$TARGET" | cut -c1-10)"
    echo "git merge --no-ff $TARGET   # 从镜像合并，或先把它推到一个可访问的 remote"
    echo "tools/apply-divergences.sh  # 重放已登记的偏离"
    echo "./gradlew testDebugUnitTest lintDebug"
    echo "python3 tools/check-upstreams.py"
    echo "tools/sync-upstream.sh $UPSTREAM_ID --record $TARGET"
    echo '```'
    echo
    echo "**构建或测试失败时：保留现有稳定主干，不要合并。**"
} > "$REPORT"

info "摘要已写入 $REPORT"
echo
echo "--- 摘要预览（前 40 行）---"
head -40 "$REPORT"
