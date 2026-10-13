#!/usr/bin/env python3
"""引用完整性检查。

检查两类容易悄悄失效的引用：
  1. Markdown 内部链接（含锚点）指向的文件是否真实存在；
  2. 文档中提到的仓库内路径（如 `tools/xxx.sh`、`governance/yyy.json`）是否存在。

存在的理由很具体：一次交付曾经只把顶层 Markdown 导出，漏掉了 `governance/`、
`tools/`、`.github/` 这些目录，而没有任何检查发现它。文档声称存在的东西必须真的存在。

约定：
  - `DSHA:tools/x.py` / `DSHBox:docs/y.md` 表示**上游仓库**内的路径，
    不是本仓库的路径，不做存在性检查。
  - `<!-- future: docs/xxx/ -->` 明确标注「未来阶段才会创建」的路径，
    检查器跳过，但人仍然看得见它尚不存在。
  - `<!-- ignore-ref: docs/nope.md -->` 标注**故意引用不存在路径**的地方
    （例如验证报告里的测试用例），检查器跳过。
  - `governance/upstream-licenses/` 是上游许可原件的冻结副本，
    内容必须保持上游原样，其内部引用的是上游路径，整体跳过。

用法：
    python3 tools/check-references.py
    python3 tools/check-references.py --verbose
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

TEXT_SUFFIXES = {".md", ".json", ".yml", ".yaml", ".sh", ".py", ".txt"}

# 上游仓库路径前缀
UPSTREAM_PREFIX = re.compile(r"^(?:DSHA|DSHBox)\s*:\s*")

# 反引号里出现的、看起来像仓库内路径的东西
BACKTICK_PATH = re.compile(
    r"`(?P<path>"
    r"(?:governance|docs|tools|\.github)/[A-Za-z0-9_./*-]+"
    r"|AGENTS\.md|README\.md|LICENSE-PENDING\.md"
    r")`"
)

MD_LINK = re.compile(r"\[(?P<text>[^\]]*)\]\((?P<target>[^)\s]+)\)")

EXTERNAL_PREFIXES = ("http://", "https://", "mailto:", "#", "tel:")

# 上游许可原件的冻结副本：整体跳过（内部引用的是上游路径）
FROZEN_DIRS = ("governance/upstream-licenses",)

FUTURE_MARKER = re.compile(r"<!--\s*future:\s*(?P<path>[^\s]+)\s*-->")

# 故意引用不存在路径的地方（例如验证报告里的失败路径测试用例）
IGNORE_REF_MARKER = re.compile(r"<!--\s*ignore-ref:\s*(?P<path>[^\s]+)\s*-->")

# 占位符路径（文档里用来说明格式的示例，如 `tools/xxx.sh`）不算真路径。
PLACEHOLDER = re.compile(r"(xxx|yyy|zzz|<[^>]+>|\*\*)", re.IGNORECASE)

failures: list[str] = []
checked_links = 0
checked_paths = 0
skipped: list[str] = []


def strip_anchor(target: str) -> str:
    return target.split("#", 1)[0]


def is_glob(path: str) -> bool:
    return any(c in path for c in "*?[")


def glob_has_match(path: str) -> bool:
    return any(ROOT.glob(path))


def path_resolves(candidate: str, rel_dir: Path) -> bool:
    """行内路径是否真实存在。

    依次尝试两种基准，任一成立即算通过：
      1. 相对本文档所在目录 —— Markdown 里写目录名时通常就是这个意思
      2. 相对仓库根 —— DSHFusion 自己文档的惯用写法

    只认第 2 种会误报上游嵌套文档里的相对引用；只认第 1 种会误报根相对引用。
    """
    for base in (rel_dir, ROOT):
        if (base / candidate).exists():
            return True
    return any(ROOT.glob(candidate)) or any(rel_dir.glob(candidate))


def is_frozen(relative: Path) -> bool:
    return any(str(relative).startswith(d) for d in FROZEN_DIRS)


def collect_skipped_paths(text: str) -> set[str]:
    """future 与 ignore-ref 两类标注都表示「不要检查这个路径」。"""
    return {m.group("path") for m in FUTURE_MARKER.finditer(text)} | {
        m.group("path") for m in IGNORE_REF_MARKER.finditer(text)
    }


def check_markdown_links(path: Path) -> None:
    global checked_links
    text = path.read_text(encoding="utf-8")
    rel_dir = path.parent
    future = collect_skipped_paths(text)

    for match in MD_LINK.finditer(text):
        target = match.group("target")
        if target.startswith(EXTERNAL_PREFIXES):
            continue

        bare = strip_anchor(target)
        if not bare:
            continue

        if UPSTREAM_PREFIX.match(bare) or bare in future:
            skipped.append(f"{path.relative_to(ROOT)} -> {bare}")
            continue

        checked_links += 1
        resolved = (rel_dir / bare).resolve()

        if not resolved.exists():
            failures.append(
                f"{path.relative_to(ROOT)}: 链接指向不存在的路径 -> {target}"
            )


def check_backtick_paths(path: Path) -> None:
    global checked_paths
    text = path.read_text(encoding="utf-8")
    future = collect_skipped_paths(text)
    rel_dir = path.parent

    for match in BACKTICK_PATH.finditer(text):
        candidate = match.group("path")
        checked_paths += 1

        if UPSTREAM_PREFIX.match(candidate):
            continue

        # 示例占位路径：不是真实引用
        if PLACEHOLDER.search(candidate):
            continue

        if candidate in future:
            skipped.append(f"{path.relative_to(ROOT)} -> {candidate}")
            continue

        if is_glob(candidate):
            if not glob_has_match(candidate):
                failures.append(
                    f"{path.relative_to(ROOT)}: 通配路径无任何匹配 -> `{candidate}`"
                )
            continue

        # 行内路径按两种基准解析：先相对本文档所在目录，再相对仓库根。
        # 只按仓库根解析是错的：上游嵌套文档里的相对路径指的是它自己所在目录，
        # 按根解析会把有效引用误报成断链（导入阶段实测到 2 处）。
        # Markdown 链接（check_markdown_links）本来就是文件相对，这里与之一致。
        if not path_resolves(candidate, rel_dir):
            failures.append(
                f"{path.relative_to(ROOT)}: 提到的仓库内路径不存在 -> `{candidate}`"
            )


def main() -> int:
    parser = argparse.ArgumentParser(description="引用完整性检查")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    files = [
        p
        for p in ROOT.rglob("*")
        if p.is_file() and p.suffix in TEXT_SUFFIXES and ".git" not in p.parts
    ]

    if args.verbose:
        print(f"扫描 {len(files)} 个文本文件")

    for path in sorted(files):
        if is_frozen(path.relative_to(ROOT)):
            continue
        if path.suffix == ".md":
            check_markdown_links(path)
        check_backtick_paths(path)

    if args.verbose and skipped:
        print(f"\n按约定跳过 {len(set(skipped))} 处（上游路径或已标注的未来路径）：")
        for item in sorted(set(skipped)):
            print(f"  · {item}")
        print()

    if failures:
        print(f"\n引用检查失败（{len(failures)} 项）：\n", file=sys.stderr)
        for message in failures:
            print(f"  ✗ {message}", file=sys.stderr)
        print("", file=sys.stderr)
        return 1

    print(
        f"引用检查通过：{checked_links} 个 Markdown 链接、"
        f"{checked_paths} 个仓库内路径引用全部有效"
        f"（另有 {len(set(skipped))} 处按约定跳过）。"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
