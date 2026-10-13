#!/usr/bin/env python3
"""对照继承基线检查 shellcheck 结果（阶段 1 起生效）。

它解决什么问题
--------------
阶段 1 把 DSHBox 的 `tools/*.sh` 原样导入主干，这些文件带着 9 条上游既有的
shellcheck 告警。原来的门禁是「tools/*.sh 必须零告警」，于是导入当天就红了。

但正确的反应**不是**去改那 9 处 —— 那样每次合并上游都会冲突，而长期吸收上游
更新正是这个项目存在的理由（见 AGENTS.md 编码约定）。正确反应是让门禁能
区分「继承的问题」和「新引入的问题」。

判定规则（三条，缺一不可）
--------------------------
1. 实际告警里有、基线里没有  → 失败（新引入）
2. 基线里有、实际告警里没有  → 失败（基线过期，防止基线变成永久压制）
3. 两边都有但位置不同        → 失败（行号漂移，说明上游改动过这些文件）

第 2、3 条是关键：没有它们，这个基线就只是一张「忽略列表」，
任何问题一旦登记进去就再也不会有人看。

用法
----
    python3 tools/check-lint-baseline.py            # 检查
    python3 tools/check-lint-baseline.py --list     # 只打印当前实际告警

退出码：0 一致；1 不一致或环境缺 shellcheck；2 用法/配置错误。
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "governance" / "inherited-lint-baseline.json"


def run_shellcheck() -> list[dict]:
    """跑 shellcheck 并返回扁平结果列表。

    注意：`shellcheck -f json` 输出的是**扁平数组**，每个元素是一条告警，
    自带 `file` 字段 —— 不是「每个文件一个对象、里面放 comments」。
    按后者解析会得到空列表，从而让门禁静默全过（这个坑踩过一次）。
    """
    scripts = sorted(str(p.relative_to(ROOT)) for p in (ROOT / "tools").glob("*.sh"))
    if not scripts:
        raise SystemExit("tools/ 下没有 .sh 文件，检查范围为空 —— 视为配置错误")

    proc = subprocess.run(
        ["shellcheck", "-f", "json", "-S", "warning", *scripts],
        cwd=ROOT, capture_output=True, text=True,
    )
    if not proc.stdout.strip():
        # shellcheck 在「无告警」时输出 `[]`；空 stdout 说明它自己出错了
        if proc.returncode not in (0, 1):
            raise SystemExit(f"shellcheck 执行失败（退出码 {proc.returncode}）：{proc.stderr[:400]}")
        return []
    return json.loads(proc.stdout)


def key_of(f: dict) -> tuple:
    return (f["file"], int(f["line"]), int(f["column"]), f["code"])


def key_of_sc(c: dict) -> tuple:
    # shellcheck 的 file 字段可能是绝对路径，统一成相对仓库根
    p = c["file"]
    try:
        p = str(Path(p).resolve().relative_to(ROOT))
    except ValueError:
        p = p.split("/dshfusion/")[-1]
    return (p, int(c["line"]), int(c["column"]), f"SC{c['code']}")


def main() -> int:
    ap = argparse.ArgumentParser(description="对照继承基线检查 shellcheck 结果")
    ap.add_argument("--list", action="store_true", help="只打印当前实际告警，不做比对")
    args = ap.parse_args()

    if not shutil.which("shellcheck"):
        print("未找到 shellcheck —— 无法检查。")
        return 1

    actual = run_shellcheck()

    if args.list:
        print(f"当前 shellcheck 告警（-S warning，tools/*.sh）共 {len(actual)} 条：")
        for c in sorted(actual, key=key_of_sc):
            f, ln, col, code = key_of_sc(c)
            print(f"  {c['level']:7} {code:8} {f}:{ln}:{col}")
        return 0

    if not BASELINE.exists():
        print(f"缺少基线文件 {BASELINE.relative_to(ROOT)}")
        return 2

    base = json.loads(BASELINE.read_text(encoding="utf-8"))
    entries = base["shellcheck"]["findings"]
    expected = {key_of(e): e for e in entries}
    got = {key_of_sc(c): c for c in actual}

    new = sorted(set(got) - set(expected))
    stale = sorted(set(expected) - set(got))

    print(f"基线登记 {len(expected)} 条，实际 {len(got)} 条。")

    if not new and not stale:
        print("\n✓ 与继承基线完全一致：没有新引入的告警，基线也没有过期。")
        print(f"  （这 {len(expected)} 条全部来自上游导入文件，"
              f"登记在 governance/inherited-lint-baseline.json）")
        return 0

    if new:
        print(f"\n✗ 新引入 {len(new)} 条告警（基线里没有）：")
        for k in new:
            f, ln, col, code = k
            print(f"    {code:8} {f}:{ln}:{col}")
            print(f"             {got[k]['message'][:90]}")
        print("\n  这些是本项目自己引入的，必须修。")
        print("  如果确实无法修且理由充分，才登记进基线 —— 并写清 functional_impact。")

    if stale:
        print(f"\n✗ 基线过期 {len(stale)} 条（登记了但实际不存在）：")
        for k in stale:
            f, ln, col, code = k
            print(f"    {code:8} {f}:{ln}:{col}")
        print("\n  常见原因：上游改动使行号漂移，或该问题已被修复。")
        print("  请更新 governance/inherited-lint-baseline.json，不要放任基线失真。")

    return 1


if __name__ == "__main__":
    sys.exit(main())
