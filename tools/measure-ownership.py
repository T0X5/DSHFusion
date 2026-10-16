#!/usr/bin/env python3
"""量「有多少是自己的」。

## 为什么需要它

用户 2026-10-16 的原话：

> 现在这个项目产品感觉就是 fork 的 DSHBox 然后在上面加一些 DSHA 的功能，
> 更像是二改作品。我想要的是自己的项目。

**「像不像二改」不该用感觉判断。** 这个工具把它变成一个数字，并让 CI 盯着它 ——
这样「在做自己的东西」就是可验证的，而不是一句自我评价。

## 分类从哪来（不硬编码路径）

四类的依据全部来自**治理登记**，不是我在脚本里猜的：

| 类 | 依据 |
|---|---|
| `dsha_import` | `governance/dsha-imports.json` 的 `files[]` + `derived.items[]` |
| `ours` | `dsha-imports.json` 的 `original.items[]` + `tools/`、`governance/`、`docs/`、自有品牌图形 |
| `backbone_diverged` | `governance/divergences.json` 的 `target_files` 命中、且不属于上面两类 |
| `backbone` | 其余全部跟踪文件（DSHBox 原样） |

**所以这份数字是「登记驱动的」**：登记错了，数字就错了。这与本仓库其他检查同源。

## 用法

    python3 tools/measure-ownership.py            # 打印 + 写 governance/ownership.json
    python3 tools/measure-ownership.py --check    # 只比对，不写（CI 用）
"""
from __future__ import annotations

import argparse
import datetime
import fnmatch
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
IMPORTS = ROOT / "governance" / "dsha-imports.json"
DIVERGENCES = ROOT / "governance" / "divergences.json"
RECORD = ROOT / "governance" / "ownership.json"

# 纯文本才数行；二进制按 0 行计（但仍然计数文件数）
SKIP_LINE_EXT = {".png", ".jpg", ".jpeg", ".webp", ".so", ".jar", ".zst", ".gz", ".asc", ".ttf", ".bin", ".ico"}


def expand_braces(pattern: str) -> list[str]:
    m = re.search(r"\{([^{}]*)\}", pattern)
    if not m:
        return [pattern]
    out: list[str] = []
    for alt in m.group(1).split(","):
        out.extend(expand_braces(pattern[: m.start()] + alt + pattern[m.end():]))
    return out


def matches_any(path: str, patterns: list[str]) -> bool:
    for pat in patterns:
        for expanded in expand_braces(pat):
            if path == expanded or fnmatch.fnmatch(path, expanded):
                return True
    return False


def tracked() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True)
    return [p for p in out.stdout.splitlines() if p]


def lines_of(rel: str) -> int:
    p = ROOT / rel
    if p.suffix.lower() in SKIP_LINE_EXT or not p.is_file():
        return 0
    try:
        return sum(1 for _ in p.open(encoding="utf-8", errors="replace"))
    except OSError:
        return 0


def classify(files: list[str]) -> dict[str, list[str]]:
    imports = json.loads(IMPORTS.read_text(encoding="utf-8"))
    divs = json.loads(DIVERGENCES.read_text(encoding="utf-8"))

    dsha = {i["local_path"] for i in imports["files"]}
    dsha |= {i["local_path"] for i in imports["derived"]["items"]}
    ours = {i["local_path"] for i in imports["original"]["items"]}

    div_patterns: list[str] = []
    for e in list(divs.get("planned", [])) + list(divs.get("applied", [])):
        div_patterns.extend(e.get("target_files") or [])

    buckets: dict[str, list[str]] = {"ours": [], "dsha_import": [], "backbone_diverged": [], "backbone": []}
    for f in files:
        # `tools/` `governance/` `docs/` 全部是自写 —— 它们是本仓库的治理与文档，两个上游都没有
        if f.startswith(("tools/", "governance/", "docs/")) or f in ours:
            buckets["ours"].append(f)
        elif f in dsha:
            buckets["dsha_import"].append(f)
        elif matches_any(f, div_patterns):
            buckets["backbone_diverged"].append(f)
        else:
            buckets["backbone"].append(f)
    return buckets


def measure() -> dict:
    files = tracked()
    buckets = classify(files)
    stats = {k: {"files": len(v), "lines": sum(lines_of(f) for f in v)} for k, v in buckets.items()}
    total_files = sum(s["files"] for s in stats.values())
    total_lines = sum(s["lines"] for s in stats.values())
    ours_lines = stats["ours"]["lines"]
    return {
        "schema": 1,
        "measured_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"),
        "method": (
            "分类由 governance/dsha-imports.json 与 governance/divergences.json 驱动，"
            "不硬编码路径。`tools/`、`governance/`、`docs/` 一律算自写 —— "
            "它们是本仓库的治理与文档，两个上游都没有。"
        ),
        "categories": stats,
        "total": {"files": total_files, "lines": total_lines},
        "ours_ratio_lines": round(ours_lines / total_lines, 4) if total_lines else 0.0,
        "ours_ratio_files": round(stats["ours"]["files"] / total_files, 4) if total_files else 0.0,
        "note": (
            "⚠️ **这个比例的口径是「行数」，而它低估了自写部分。** "
            "`tools/`+`governance/`+`docs/` 共 75+ 个文件是纯自写，"
            "但它们不是 Kotlin/Java 代码，行数远小于骨干。"
            "**不要把「9%」读成「这个项目只有 9% 是自己的」** —— "
            "读成「Kotlin/Java 代码里 9% 是自己写的」。"
        ),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只比对，不写（CI 用）")
    args = ap.parse_args()

    now = measure()
    if not args.check:
        RECORD.write_text(json.dumps(now, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for k, s in now["categories"].items():
            print(f"  {k:20s} {s['files']:4d} 文件 {s['lines']:7d} 行")
        print(f"  {'=' * 20}")
        print(f"  {'合计':20s} {now['total']['files']:4d} 文件 {now['total']['lines']:7d} 行")
        print(f"  自写占比（行）: {now['ours_ratio_lines']:.2%}   已写入 governance/ownership.json")
        return 0

    if not RECORD.is_file():
        print("governance/ownership.json 不存在 —— 先跑一次不带 --check 的", file=sys.stderr)
        return 1
    was = json.loads(RECORD.read_text(encoding="utf-8"))
    before = float(was.get("ours_ratio_lines", 0.0))
    after = now["ours_ratio_lines"]
    print(f"  自写占比：记录 {before:.2%} → 实测 {after:.2%}")
    if after + 1e-9 < before:
        print(
            f"✗ 自写占比**下降了**（{before:.2%} → {after:.2%}）。\n"
            "  若这是有意的（例如又导入了一批上游代码），请显式更新\n"
            "  governance/ownership.json 并把原因写进提交信息 ——\n"
            "  不这么做的话，「在做自己的东西」就只是一句自我评价。",
            file=sys.stderr)
        return 1
    print("  ✓ 自写占比没有下降")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
