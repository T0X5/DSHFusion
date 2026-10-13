#!/usr/bin/env bash
# 为 --record 的校验路径构造一个隔离的测试夹具。
#
# 为什么需要它：--record 是唯一会改动 governance/upstreams.json 的动作，
# 而它的失败路径（提交不存在、来源不对、关系回退、缺审核记录）在真实仓库上
# 无法安全制造 —— 真实镜像的 HEAD 就是锁定提交，没有"更靠前的提交"可用。
# 所以在一个临时目录里造一个自包含的假上游与假仓库。
#
# 用法：
#   tools/make-record-fixture.sh <目标目录>
#
# 产出目录结构：
#   <目标目录>/
#     mirrors/DSHBox.git/       假上游镜像（bare）
#     repo/                     假 DSHFusion 仓库（含 governance/ 与 tools/）
#     commits.env               各测试用提交号
#
# 设计要点：
#   - main 有 3 个提交，locked_commit 指向 HEAD；
#   - side 分支有一个**不在 main 上**的提交（测试来源校验）；
#   - parent 是 main 的父提交（测试关系回退：它不是 HEAD 的祖先？不，它是祖先，
#     所以另造一个 orphan 提交测试"锁定提交不是新提交祖先"）；
#   - ahead 是 main 之上的新提交（测试正常路径与审核记录校验）。

set -euo pipefail

TARGET="${1:?用法: make-record-fixture.sh <目标目录>}"
[ -e "$TARGET" ] && rm -rf "$TARGET"
mkdir -p "$TARGET/mirrors" "$TARGET/repo"

MIRROR="$TARGET/mirrors/DSHBox.git"
git init -q --bare "$MIRROR"

# 允许在 bare 仓库里直接造提交
export GIT_AUTHOR_NAME=upstream
export GIT_AUTHOR_EMAIL=upstream@example.invalid
export GIT_COMMITTER_NAME=upstream
export GIT_COMMITTER_EMAIL=upstream@example.invalid

commit_file() {   # $1=文件名 $2=内容 $3=提交信息 [$4=父提交...]
    local name="$1" content="$2" message="$3"; shift 3
    local blob tree
    blob="$(printf '%s\n' "$content" | git -C "$MIRROR" hash-object -w --stdin)"
    tree="$(printf '100644 blob %s\t%s\n' "$blob" "$name" | git -C "$MIRROR" mktree)"
    git -C "$MIRROR" commit-tree "$tree" "$@" -m "$message"
}

# --- main 的三个提交 ---
C1="$(commit_file a.txt "one" "feat: 初始提交")"
C2="$(commit_file b.txt "two" "fix: 修一个问题" -p "$C1")"
C3="$(commit_file c.txt "three" "docs: 更新说明" -p "$C2")"
git -C "$MIRROR" update-ref refs/heads/main "$C3"

# --- side：不在 main 上的提交 ---
SIDE="$(commit_file side.txt "side" "chore: 旁支提交" -p "$C2")"
git -C "$MIRROR" update-ref refs/heads/side "$SIDE"

# --- ahead：main 之上的新提交（模拟上游推进）---
AHEAD="$(commit_file d.txt "four" "feat: 上游新增功能" -p "$C3")"
git -C "$MIRROR" update-ref refs/heads/main "$AHEAD"
git -C "$MIRROR" update-ref refs/heads/base "$C3"   # 记录推进前的 HEAD

# --- orphan：与 main 无共同祖先（会被"来源"校验拦下）---
ORPHAN="$(commit_file orphan.txt "orphan" "chore: 无关历史")"

# --- rewound：在 main 上、但**不是锁定提交的后代**。
# 用来单独测试"关系回退"校验：它必须通过来源校验（2），
# 却在校验 3 被拦下，证明校验 3 确实独立生效。
REWOUND="$(commit_file rewound.txt "rewound" "chore: 回退后的提交" -p "$C2")"
git -C "$MIRROR" update-ref refs/heads/rewound "$REWOUND"

# --- 假仓库 ---
REPO="$TARGET/repo"
mkdir -p "$REPO/governance" "$REPO/docs/upstream-reports" "$REPO/tools"
cp "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sync-upstream.sh" "$REPO/tools/"
cat > "$REPO/governance/upstreams.json" <<EOF
{
  "schema": 1,
  "note": "record 校验测试夹具",
  "upstreams": [
    {
      "id": "dshbox",
      "full_name": "fixture/DSHBox",
      "url": "https://example.invalid/fixture/DSHBox.git",
      "role": "base",
      "default_branch": "main",
      "license": "GPL-3.0",
      "locked_commit": "$C3",
      "locked_commit_date": "2026-01-01T00:00:00Z"
    }
  ]
}
EOF
cat > "$REPO/docs/upstream-reports/README.md" <<'EOF'
# 夹具用摘要目录
EOF
git -C "$REPO" init -q
git -C "$REPO" -c user.email=f@f -c user.name=f add -A
git -C "$REPO" -c user.email=f@f -c user.name=f commit -q -m "fixture"

cat > "$TARGET/commits.env" <<EOF
# 夹具提交号（供测试脚本 source）
FIX_C1=$C1
FIX_C2=$C2
FIX_C3=$C3
FIX_AHEAD=$AHEAD
FIX_SIDE=$SIDE
FIX_ORPHAN=$ORPHAN
FIX_REWOUND=$REWOUND
FIX_MIRROR=$MIRROR
FIX_REPO=$REPO
EOF

echo "夹具已建立：$TARGET"
echo "  锁定提交（= C3）：$C3"
echo "  main 新 HEAD：    $AHEAD"
echo "  旁支提交：        $SIDE"
echo "  无关历史提交：    $ORPHAN"
echo "  回退后提交：      $REWOUND"
