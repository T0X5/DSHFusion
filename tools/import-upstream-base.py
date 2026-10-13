#!/usr/bin/env python3
"""把 DSHBox 基线源码导入 DSHFusion 主干（阶段 1 的第一步）。

这个脚本做的是**机械动作**：按 governance/import-exclusions.json 的取舍清单，
把上游锁定提交的跟踪文件复制进来，同名文件按清单改名归档。
它不修改上游，也不碰偏离登记 —— 复制完再跑 tools/apply-divergences.sh。

安全设计
--------
1. **拒绝在 main 上运行。** 阶段 1 必须走分支 + PR，这是用户明确要求。
   在 main 上执行会直接退出，除非给出 --allow-main（不建议）。
2. **拒绝在有未提交改动时运行。** 否则一旦出错很难回退到干净状态。
3. **校验上游提交。** 上游检出的 HEAD 必须等于清单里的 locked_commit，
   否则拒绝 —— 防止把「某个时候的 DSHBox」当成锁定基线导入。
4. **默认 dry-run。** 不带 --apply 只报告将要做什么，不动任何文件。

用法
----
    # 先看计划（不改文件）
    tools/import-upstream-base.py --upstream ../audit/DSHBox

    # 在阶段 1 分支上真正执行
    git switch -c phase-1/import-dshbox-base
    tools/import-upstream-base.py --upstream ../audit/DSHBox --apply

退出码
------
    0  成功（dry-run 或实际导入）
    1  前置条件不满足（分支不对、有未提交改动、上游提交不符、清单缺失）
    2  用法错误
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "governance/import-exclusions.json"

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def git(*args: str, cwd: Path | None = None) -> str:
    return subprocess.run(["git", *args], cwd=cwd or ROOT,
                          capture_output=True, text=True, check=True).stdout.strip()


def fail(msg: str) -> int:
    print(f"{RED}拒绝执行：{msg}{RESET}", file=sys.stderr)
    return 1


def main() -> int:
    ap = argparse.ArgumentParser(description="导入 DSHBox 基线源码（阶段 1）")
    ap.add_argument("--upstream", required=True, help="DSHBox 检出目录（只读使用）")
    ap.add_argument("--apply", action="store_true",
                    help="真正写入文件。不带此参数只报告计划（dry-run）")
    ap.add_argument("--allow-main", action="store_true",
                    help="允许在 main 上执行。阶段 1 不应使用 —— 必须走分支 + PR")
    args = ap.parse_args()

    upstream = Path(args.upstream).resolve()
    if not upstream.is_dir():
        print(f"ERROR: 上游目录不存在：{upstream}", file=sys.stderr)
        return 2
    if not MANIFEST.is_file():
        return fail(f"取舍清单不存在：{MANIFEST.relative_to(ROOT)}")

    spec = json.loads(MANIFEST.read_text(encoding="utf-8"))
    locked = spec["locked_commit"]

    # ---- 前置条件 ----
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    if branch == "main" and not args.allow_main:
        return fail(
            "当前在 main 分支上。阶段 1 必须通过分支 + PR 实施。\n"
            "          请先执行：git switch -c phase-1/import-dshbox-base\n"
            "          （确实要在 main 上执行时加 --allow-main，但那会绕过 PR 流程）"
        )

    dirty = git("status", "--porcelain")
    if dirty:
        n = len(dirty.splitlines())
        return fail(f"工作树有 {n} 个未提交改动。请先提交或 stash，再运行导入。")

    up_head = git("rev-parse", "HEAD", cwd=upstream)
    if up_head != locked:
        return fail(
            f"上游 HEAD 与锁定提交不符。\n"
            f"          锁定：{locked}\n"
            f"          实际：{up_head}\n"
            f"          清单要求导入锁定基线，不是「任意时候的 DSHBox」。\n"
            f"          若确实要换基线，先更新 governance/upstreams.json 并走同步流程。"
        )

    # ---- 计算导入集合 ----
    all_files = [f for f in git("ls-files", cwd=upstream).splitlines() if f]

    def excluded_by(rel: str) -> dict | None:
        for rule in spec.get("excluded_paths", []):
            if rule["kind"] == "prefix" and rel.startswith(rule["path"]):
                return rule
            if rule["kind"] == "exact" and rel == rule["path"]:
                return rule
        return None

    preserved_map = {p["path"]: p for p in spec.get("preserved_files", [])}

    excluded, preserved, imported = [], [], []
    for rel in all_files:
        if (rule := excluded_by(rel)) is not None:
            excluded.append((rel, rule))
        elif rel in preserved_map:
            preserved.append(rel)
        else:
            imported.append(rel)

    def total_size(files: list[str]) -> int:
        return sum((upstream / f).stat().st_size for f in files if (upstream / f).is_file())

    # ---- 报告 ----
    mode = f"{YELLOW}实际导入{RESET}" if args.apply else f"{DIM}dry-run（不改文件）{RESET}"
    print(f"模式：{mode}")
    print(f"分支：{branch}")
    print(f"上游：{upstream}")
    print(f"锁定提交：{locked[:12]}（已核验一致）")
    print()
    print(f"  上游跟踪文件      {len(all_files):>5}")
    print(f"  排除              {len(excluded):>5}   {total_size([f for f,_ in excluded])/1024/1024:.1f} MB")
    print(f"  改名归档          {len(preserved):>5}")
    print(f"  导入              {len(imported):>5}   {total_size(imported)/1024/1024:.1f} MB")
    print()

    print(f"{DIM}排除清单（每条都登记了偏离号）：{RESET}")
    by_rule: dict[str, list[str]] = {}
    for rel, rule in excluded:
        by_rule.setdefault(rule["path"], []).append(rel)
    for path, rels in by_rule.items():
        div = next(r["divergence"] for r in spec["excluded_paths"] if r["path"] == path)
        print(f"  {path}  →  {div}  （{len(rels)} 个文件）")

    print(f"\n{DIM}同名文件保留我方版本，上游版本改名归档：{RESET}")
    for rel in preserved:
        p = preserved_map[rel]
        print(f"  {rel}  →  {p['archive_as']}")

    # ---- 与清单预期比对 ----
    exp = spec.get("expected_counts", {})
    mismatches = []
    for key, actual in (("upstream_tracked_files", len(all_files)),
                        ("excluded", len(excluded)),
                        ("preserved", len(preserved)),
                        ("imported", len(imported))):
        want = exp.get(key)
        if want is not None and want != actual:
            mismatches.append(f"  {key}: 清单记 {want}，实测 {actual}")
    print()
    if mismatches:
        print(f"{YELLOW}⚠ 与清单预期不符：{RESET}")
        for m in mismatches:
            print(m)
        print("  上游可能不是清单所记的那个提交，或清单需要更新。")
    else:
        print(f"{GREEN}✓ 与清单预期一致{RESET}")

    if not args.apply:
        print(f"\n{DIM}这是 dry-run。确认计划无误后，用 --apply 执行。{RESET}")
        return 0

    # ---- 执行 ----
    print(f"\n开始导入…")
    for rel in imported:
        dst = ROOT / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(upstream / rel, dst)

    for rel in preserved:
        src = upstream / rel
        dst = ROOT / preserved_map[rel]["archive_as"]
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    n = len(imported) + len(preserved)
    print(f"{GREEN}✓ 已写入 {n} 个文件{RESET}")
    print()
    print("下一步（按顺序）：")
    print("  1. tools/apply-divergences.sh --check    # 看哪些偏离需要重放")
    print("  2. tools/apply-divergences.sh            # 重放可自动化的偏离")
    print("  3. 人工绘制品牌图形（DIV-0002 会拒绝通过直到替换）")
    print("  4. 改写 .github/workflows/android.yml 里依赖 website/ 的步骤")
    print("  5. git add -A && git commit              # 提交后再开 PR")
    print()
    print(f"{YELLOW}注意：本脚本只做了复制。它没有构建、没有测试、没有真机验证 ——{RESET}")
    print(f"{YELLOW}      不要因此认为导入已经成功。{RESET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
