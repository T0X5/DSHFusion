#!/usr/bin/env python3
"""核对导入的上游文件是否与上游逐字节一致（阶段 1 起生效）。

它解决什么问题
--------------
阶段 1 的承诺是「把 DSHBox 基线原样导入主干」——「原样」必须是可验证的，
不能只靠导入器自己说。这个脚本逐 blob 比对两个 git 索引：
本项目索引 vs 上游审计副本索引。

比对的是 **git 索引里的 blob 哈希**，不是工作区文件内容。原因：
工作区内容会被 .gitattributes 的换行转换、本地编辑、chmod 影响，
而 blob 哈希才是别人 clone 之后真正拿到的东西。

已知会不同的文件
----------------
登记在 governance/divergences.json 里的偏离，以及
governance/import-exclusions.json 里排除/归档的文件。
脚本从这两个文件读取，而不是硬编码 —— 新增偏离后无需改脚本。

这个检查抓到过什么
------------------
2026-10-13 首次运行时发现 `gradlew.bat` 的 blob 与上游不同：
本项目 .gitattributes 的 `* text=auto eol=lf` 把它规范化成了 LF，
而上游没有 .gitattributes、原样存着 CRLF。后果是
（1）「逐字节忠实导入」不再成立，（2）Windows 上执行该批处理可能出错。
先改成 `text eol=crlf` 仍不对 —— 那只影响检出，仓库里存的还是 LF；
最终改成 `-text`（不做任何转换）才真正一致。

用法
----
    python3 tools/check-import-fidelity.py
    python3 tools/check-import-fidelity.py --list-diffs   # 只列出差异明细

退出码：
  0  一致（或上游尚未导入）
  1  存在未登记的差异
  2  配置或环境错误
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXCLUSIONS = ROOT / "governance" / "import-exclusions.json"
DIVERGENCES = ROOT / "governance" / "divergences.json"
# 上游审计副本的位置。
# 只认相对路径 —— 早先这里还写死过一个本机绝对路径，在 CI 上既不存在，
# 又会因为 PermissionError 让整个脚本崩溃（而不是优雅跳过）。
# 允许用环境变量覆盖，便于别人放在别处。
UPSTREAM_HINTS = [
    Path(os.environ["DSHFUSION_UPSTREAM_DSHBOX"])
    if os.environ.get("DSHFUSION_UPSTREAM_DSHBOX")
    else ROOT.parent / "audit" / "DSHBox",
]


def expand_braces(pattern: str) -> list[str]:
    """把 `a/{b,c}/d` 展开成 ['a/b/d', 'a/c/d']，支持多层嵌套。"""
    m = re.search(r"\{([^{}]*)\}", pattern)
    if not m:
        return [pattern]
    out: list[str] = []
    for alt in m.group(1).split(","):
        out.extend(expand_braces(pattern[: m.start()] + alt + pattern[m.end():]))
    return out


def matches_any(path: str, patterns: list[str]) -> bool:
    """path 是否匹配任一模式。模式支持花括号展开与 shell 通配符。

    divergences.json 的 target_files 就是这么写的，例如
    `app/src/main/res/values-*/strings.xml`、
    `app/src/main/res/drawable-{hdpi,mdpi}/ic_pilot_brand.png`。
    早先按字面前缀比对，导致 12 处**已登记**的差异被误报成「未登记」，
    差点让人以为导入不忠实。
    """
    for pat in patterns:
        for expanded in expand_braces(pat):
            if path == expanded or fnmatch.fnmatch(path, expanded):
                return True
            if expanded.endswith("/") and path.startswith(expanded):
                return True
    return False


def git_index(root: Path) -> dict[str, tuple[str, str]] | None:
    """读取 git 索引；不是 git 检出时返回 None。

    调用方据此跳过而不是崩溃 —— 导出成 tarball 的副本就没有 .git，
    那不是错误状态，只是这次没法核对。
    """
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-s"],
            capture_output=True, text=True, check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError, OSError):
        return None
    d: dict[str, tuple[str, str]] = {}
    for line in out.splitlines():
        meta, path = line.split("\t", 1)
        mode, sha, _ = meta.split()
        d[path] = (mode, sha)
    return d


def find_upstream() -> Path | None:
    """找到上游审计副本；找不到就返回 None（调用方据此跳过，而不是报错）。

    每个探测都要吞掉 OSError：路径可能存在但当前用户无权 stat
    （CI 上 /root/... 就是这种情况）。「无权访问」与「不存在」对调用方
    是一回事 —— 都意味着这次没法核对保真度，应当跳过而不是崩溃。
    """
    for p in UPSTREAM_HINTS:
        try:
            if (p / ".git").exists() or (p / "HEAD").exists():
                return p
        except OSError:
            continue
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description="核对导入文件与上游是否逐字节一致")
    ap.add_argument("--list-diffs", action="store_true", help="列出全部差异明细")
    args = ap.parse_args()

    up_root = find_upstream()
    if up_root is None:
        print("未找到上游审计副本 —— 跳过保真度核对。")
        return 0

    if not EXCLUSIONS.exists():
        print(f"缺少 {EXCLUSIONS.relative_to(ROOT)}")
        return 2

    exc = json.loads(EXCLUSIONS.read_text(encoding="utf-8"))
    excl_prefix = [e["path"] for e in exc.get("excluded_paths", []) if e.get("kind") == "prefix"]
    excl_exact = {e["path"] for e in exc.get("excluded_paths", []) if e.get("kind") == "exact"}
    preserved = {p["path"]: p["archive_as"] for p in exc.get("preserved_files", [])}

    def is_excluded(p: str) -> bool:
        return p in excl_exact or any(p.startswith(e.rstrip("/") + "/") for e in excl_prefix)

    # 已登记偏离登记的 target_files（支持 glob 与花括号展开）
    div_patterns: list[str] = []
    if DIVERGENCES.exists():
        dv = json.loads(DIVERGENCES.read_text(encoding="utf-8"))
        for entry in list(dv.get("planned", [])) + list(dv.get("applied", [])):
            div_patterns.extend(entry.get("target_files", []) or [])

    def is_diverged(p: str) -> bool:
        return matches_any(p, div_patterns)

    up = git_index(up_root)
    me = git_index(ROOT)
    if up is None or me is None:
        print("本仓库或上游副本不是 git 检出 —— 跳过保真度核对。")
        return 0

    content_diff, mode_diff, missing = [], [], []
    checked = 0
    for path, (umode, usha) in up.items():
        if is_excluded(path) or path in preserved:
            continue
        checked += 1
        if path not in me:
            missing.append(path)
            continue
        mmode, msha = me[path]
        if msha != usha:
            content_diff.append((path, umode, usha, mmode, msha))
        elif mmode != umode:
            mode_diff.append((path, umode, mmode))

    unregistered = [d for d in content_diff + [(*m, None, None) for m in mode_diff]
                    if not is_diverged(d[0])]
    registered = [d for d in content_diff + [(*m, None, None) for m in mode_diff]
                  if is_diverged(d[0])]
    missing_unexpected = [p for p in missing if not is_diverged(p)]

    print(f"上游跟踪文件   : {len(up)}")
    print(f"应导入并已比对 : {checked}")
    print(f"与上游不同     : {len(content_diff) + len(mode_diff)}"
          f"（其中已登记偏离 {len(registered)}）")
    print(f"缺失           : {len(missing)}（其中已登记 {len(missing) - len(missing_unexpected)}）")

    if args.list_diffs:
        print("\n--- 已登记的差异（预期）---")
        for path, umode, usha, mmode, msha in registered:
            print(f"  {path}\n      上游 {usha} → 本地 {msha}")
        for p in missing:
            if is_diverged(p):
                print(f"  缺失（已登记）{p}")

    if unregistered or missing_unexpected:
        print(f"\n✗ 发现 {len(unregistered)} 处未登记的差异、"
              f"{len(missing_unexpected)} 个未登记的缺失：")
        for path, umode, usha, mmode, msha in unregistered:
            print(f"    {path}")
            print(f"      上游 {usha}  本地 {msha}")
            print(f"      → 若这是有意的，请登记进 governance/divergences.json；"
                  f"否则说明导入不忠实。")
        for p in missing_unexpected:
            print(f"    缺失 {p}")
        return 1

    print("\n✓ 所有差异都能对上已登记的偏离；其余文件与上游逐字节（含模式）一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
