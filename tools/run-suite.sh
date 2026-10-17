#!/usr/bin/env bash
# 跑阶段验证套件，并**保证你读到的日志就是这一次的**。
#
# 为什么需要它（事故第 48 条）
# ----------------------------
# 我用命令链后台启动套件时，启动被命令链吞掉了；随后我去读「上一轮的临时日志」，
# 读到的是一份**两天前**的那一份，它报「315 项通过」—— 一个非常健康的数字。
# 它报「315 项通过」—— 一个非常健康的数字。
# **假绿比红贵得多**：红会迫使你看，绿不会。
#
# 所以这里做两件事：
#   ① 日志名里带**时间戳与提交号**，不靠人记；
#   ② 读之前先跑 `--check`，它把日志头和**当前** HEAD 比 —— 对不上就退 1。
#
# 用法
# ----
#   tools/run-suite.sh              # 跑；日志写 <日志目录>/dshfusion-suite-<时间>-<sha>.log
#   tools/run-suite.sh --check FILE # 校验 FILE 是不是「这一次、这个提交」的（0 是，1 不是）
#   tools/run-suite.sh --last       # 打印最近一份日志的路径与结论行
#
# 退出码：套件本身的退出码；`--check` 用 0/1；找不到日志 3。

set -uo pipefail
cd "$(dirname "$0")/.." || exit 3

LOG_DIR=${SUITE_LOG_DIR:-/tmp}

# 日志头会写这三行；--check 拿它们与当前状态比。
header_time() { sed -n 's/^时间: //p' "$1" | head -1; }
header_sha()  { sed -n 's/^提交: //p'  "$1" | head -1; }
current_sha() { git rev-parse HEAD 2>/dev/null; }

check_log() {
    local file=$1
    if [ ! -f "$file" ]; then
        echo "没有这个日志：$file" >&2
        return 3
    fi
    local sha head_time
    sha=$(header_sha "$file")
    head_time=$(header_time "$file")
    if [ -z "$sha" ] || [ -z "$head_time" ]; then
        # 🔴 读不到头 = **不能**当成「这一次的」。这正是事故 48 的形态：
        #    文件存在、内容看着健康、但它不是这一轮产生的。
        echo "拒绝：$file 没有可核验的日志头（时间/提交）—— 不能当成这一次的结果" >&2
        return 1
    fi
    if [ "$sha" != "$(current_sha)" ]; then
        echo "拒绝：$file 是提交 ${sha:0:7} 的，而当前是 $(current_sha | cut -c1-7)" >&2
        echo "      （时间戳 $head_time）" >&2
        return 1
    fi
    # 时间必须晚于 HEAD 的提交时间 —— 早于它不可能是「跑了这个提交」。
    local head_epoch log_epoch
    head_epoch=$(git log -1 --format=%ct 2>/dev/null || echo 0)
    log_epoch=$(date -d "$head_time" +%s 2>/dev/null || echo 0)
    if [ "$log_epoch" -gt 0 ] && [ "$head_epoch" -gt 0 ] && [ "$log_epoch" -lt "$head_epoch" ]; then
        echo "拒绝：$file 的时间戳早于 HEAD 的提交时间 —— 它不可能是这一个提交的结果" >&2
        return 1
    fi
    echo "日志可信：$(basename "$file")  ← 提交 ${sha:0:7}，时间 $head_time"
    return 0
}

case "${1:-}" in
    --check)
        check_log "${2:?--check 需要日志路径}"
        exit $?
        ;;
    --last)
        last=$(ls -1t "$LOG_DIR"/dshfusion-suite-*.log 2>/dev/null | head -1)
        [ -n "$last" ] || { echo "没有找到套件日志" >&2; exit 3; }
        echo "$last"
        grep -E '^[[:space:]]+通过: |^[[:space:]]+失败: ' "$last" | tail -2
        exit 0
        ;;
esac

sha=$(current_sha)
short=$(printf '%s' "$sha" | cut -c1-7)
stamp=$(date +%Y%m%dT%H%M%S)
log="$LOG_DIR/dshfusion-suite-$stamp-$short.log"

echo "日志：$log"
echo "提交：$short"
bash tools/verify-phase0.sh >"$log" 2>&1
status=$?      # 别写进 if 分支里 —— 那里拿到的是取反后的状态

# **无论成败都把结论行打出来** —— 事故第 45 条：跑了不等于读了结论行。
grep -E '^[[:space:]]+通过: |^[[:space:]]+失败: ' "$log" | tail -2
if ! check_log "$log"; then
    echo "⚠️ 日志不可信（见上），不要引用它的结论" >&2
    exit 4
fi
exit "$status"
