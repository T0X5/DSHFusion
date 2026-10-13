#!/usr/bin/env python3
"""对照真实上游检出，核验 5 处偏离的锚点是否仍然成立。

为什么需要这个工具
------------------
`tools/apply-divergences.sh` 的每个处理器都依赖上游源码里的**具体字面量**
（固定字符串或正则）。上游一旦改写这些位置，重放就会失败 —— 但只有在真正
执行时才失败，而那时可能已经合并了上游。

这个工具把「锚点还在不在」变成一条可以提前跑的检查：
在导入上游之前、以及每次上游同步之后跑一次，就能在动手前知道哪一处会炸。

锚点表与实现的一致性
--------------------
本工具的锚点必须与 `tools/apply-divergences.sh` 里处理器用的完全一致。
为了避免两边各自漂移，每个锚点都带一段 `witness`（标识文本），
工具会校验它确实出现在实现脚本里。校验不过就退出 —— 那说明处理器改了写法
而这里没跟着改，此时检查结果没有意义。

用法
----
    tools/check-divergence-anchors.py --upstream ../audit/DSHBox
    tools/check-divergence-anchors.py --upstream <任意 DSHBox 检出> --verbose

退出码
------
    0  全部锚点成立
    1  有锚点缺失（打印具体是哪一处、期望什么、去哪里改）
    2  用法错误、上游目录不存在，或自我校验失败
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APPLY_SCRIPT = ROOT / "tools/apply-divergences.sh"
DIVERGENCES = ROOT / "governance/divergences.json"

# 品牌图形锚点由 divergences.json 的该字段驱动，单独处理。
BRAND_KEY = "upstream_sha256"

GREEN, RED, DIM, RESET = "\033[32m", "\033[31m", "\033[2m", "\033[0m"


@dataclass(frozen=True)
class Anchor:
    div_id: str
    target: str          # 相对上游检出根的路径（文件或目录）
    kind: str            # literal | regex | glob_regex | exists
    pattern: str         # literal=子串；regex/glob_regex=正则；exists=空
    desc: str            # 给人看的说明
    witness: str         # 必须出现在 apply-divergences.sh 里的标识文本

    def witness_problem(self, script: str) -> str | None:
        if self.witness and self.witness not in script:
            return (f"{self.div_id}: 标识文本在 apply-divergences.sh 里找不到 —— {self.desc}\n"
                    f"          期望出现：{self.witness}")
        return None


ANCHORS: list[Anchor] = [
    Anchor("DIV-0001", "app/build.gradle.kts", "regex",
           r'applicationId\s*=?\s*"com\.dshbox\.app"',
           "applicationId 的赋值（handler 用该正则定位并替换）",
           r'"com\.dshbox\.app"'),
    Anchor("DIV-0001", "app/build.gradle.kts", "regex",
           r'namespace\s*=?\s*"com\.dshbox\.app"',
           "namespace 的赋值（handler 据此判断是否需要一并改）",
           r'"com\.dshbox\.app"'),
    Anchor("DIV-0002", "app/src/main/res", "glob_regex",
           r'<string name="app_name">DSHBox</string>',
           "app_name 条目（在 values*/strings.xml 中至少出现一次）",
           '<string name="app_name">DSHBox</string>'),
    Anchor("DIV-0003", "app/build.gradle.kts", "literal",
           'assets.srcDirs("../../runtime/android-assets")',
           "assets.srcDirs 调用（handler 的固定字符串替换目标）",
           'assets.srcDirs("../../runtime/android-assets")'),
    Anchor("DIV-0004", "app/src/main/java/com/dshbox/app/util/AppVersion.kt", "literal",
           "https://wsk-build.github.io/DSHBox/",
           "SITE_URL 的字面量",
           "https://wsk-build.github.io/DSHBox/"),
    Anchor("DIV-0004", "app/src/main/java/com/dshbox/app/util/AppVersion.kt", "literal",
           "https://github.com/WSK-build/DSHBox",
           "REPO_URL 的字面量",
           "https://github.com/WSK-build/DSHBox"),
    Anchor("DIV-0004", "app/src/main/java/com/dshbox/app/util/AppVersion.kt", "literal",
           "https://api.github.com/repos/WSK-build/DSHBox/releases/latest",
           "API_URL 的字面量",
           "https://api.github.com/repos/WSK-build/DSHBox/releases/latest"),
    Anchor("DIV-0005", "website", "exists", "",
           "website/ 目录（上游存在，DSHFusion 刻意不导入）",
           ""),
]


def self_check() -> list[str]:
    """确认锚点表与 apply-divergences.sh 的实现一致。"""
    if not APPLY_SCRIPT.is_file():
        return [f"找不到 {APPLY_SCRIPT.relative_to(ROOT)}"]
    script = APPLY_SCRIPT.read_text(encoding="utf-8")
    return [p for a in ANCHORS if (p := a.witness_problem(script)) is not None]


def load_brand_anchors(divergences: Path) -> dict[str, str]:
    doc = json.loads(divergences.read_text(encoding="utf-8"))
    for entry in list(doc.get("applied", [])) + list(doc.get("planned", [])):
        if entry.get("id") == "DIV-0002":
            return entry.get(BRAND_KEY) or {}
    return {}


def evaluate(anchor: Anchor, upstream: Path) -> tuple[bool, str]:
    path = upstream / anchor.target

    if anchor.kind == "exists":
        return path.exists(), f"{anchor.target} {'存在' if path.exists() else '不存在'}"

    if anchor.kind == "glob_regex":
        if not path.is_dir():
            return False, f"{anchor.target} 目录不存在"
        files = sorted(path.glob("values*/strings.xml"))
        if not files:
            return False, "没有找到任何 values*/strings.xml"
        hits = [f for f in files
                if re.search(anchor.pattern, f.read_text(encoding="utf-8", errors="replace"))]
        return bool(hits), f"扫描 {len(files)} 个 strings.xml，{len(hits)} 个匹配"

    if not path.is_file():
        return False, f"{anchor.target} 文件不存在"

    content = path.read_text(encoding="utf-8", errors="replace")
    if anchor.kind == "literal":
        ok = anchor.pattern in content
        return ok, f"{anchor.target} 中{'找到' if ok else '找不到'}该字面量"
    ok = re.search(anchor.pattern, content) is not None
    return ok, f"{anchor.target} 中{'匹配到' if ok else '匹配不到'}该正则"


def main() -> int:
    ap = argparse.ArgumentParser(description="核验偏离锚点在真实上游检出中是否成立")
    ap.add_argument("--upstream", required=True,
                    help="DSHBox 检出目录（只读使用，不会被修改）")
    ap.add_argument("--divergences", default=str(DIVERGENCES),
                    help="偏离登记文件，默认用本仓库的 governance/divergences.json。"
                         "主要供隔离夹具注入自有登记，正常使用不要改")
    ap.add_argument("--verbose", action="store_true", help="打印每个锚点的判定细节")
    args = ap.parse_args()

    upstream = Path(args.upstream).resolve()
    if not upstream.is_dir():
        print(f"ERROR: 上游目录不存在：{upstream}", file=sys.stderr)
        return 2

    divergences = Path(args.divergences)
    if not divergences.is_file():
        print(f"ERROR: 偏离登记文件不存在：{divergences}", file=sys.stderr)
        return 2

    problems = self_check()
    if problems:
        print("自我校验失败 —— 锚点表与 apply-divergences.sh 已不一致，"
              "此时的检查结果没有意义：", file=sys.stderr)
        for p in problems:
            print(f"  ✗ {p}", file=sys.stderr)
        print("\n请先让两者对齐，再运行本检查。", file=sys.stderr)
        return 2

    print(f"上游检出：{upstream}")
    print("锚点表已与 apply-divergences.sh 对齐（自我校验通过）")
    print()

    by_div: dict[str, list[tuple[bool, str, str]]] = {}
    for anchor in ANCHORS:
        ok, detail = evaluate(anchor, upstream)
        by_div.setdefault(anchor.div_id, []).append((ok, anchor.desc, detail))

    # 品牌图形：逐个核对上游哈希是否与登记一致
    for rel, want in sorted(load_brand_anchors(divergences).items()):
        path = upstream / rel
        if not path.is_file():
            by_div.setdefault("DIV-0002", []).append(
                (False, f"品牌图形 {rel}", "文件不存在"))
            continue
        got = hashlib.sha256(path.read_bytes()).hexdigest()
        by_div.setdefault("DIV-0002", []).append(
            (got == want, f"品牌图形 {rel}",
             "哈希与登记一致" if got == want
             else f"哈希已变（登记 {want[:12]}…，实际 {got[:12]}…）"))

    total = failed = 0
    for div_id in sorted(by_div):
        rows = by_div[div_id]
        n_bad = sum(1 for ok, _, _ in rows if not ok)
        total += len(rows)
        failed += n_bad
        mark = f"{GREEN}✓{RESET}" if n_bad == 0 else f"{RED}✗{RESET}"
        print(f"  {mark} {div_id}  {len(rows) - n_bad}/{len(rows)} 个锚点成立")
        for ok, desc, detail in rows:
            if args.verbose or not ok:
                icon = f"{GREEN}·{RESET}" if ok else f"{RED}·{RESET}"
                print(f"      {icon} {desc}")
                print(f"        {DIM}{detail}{RESET}")

    print()
    if failed:
        print(f"{RED}==> {failed}/{total} 个锚点不成立。{RESET}")
        print("    说明上游改写了这些位置。**在更新对应 handler 之前，"
              "偏离重放会失败** —— 不要先合并上游。")
        print("    处理方式：人工确认上游的新写法，然后同时更新")
        print("      - tools/apply-divergences.sh 的对应 handler")
        print("      - 本工具的锚点表")
        print("      - governance/divergences.json 的 reapply 字段")
        return 1

    print(f"{GREEN}==> {total} 个锚点全部成立。{RESET}")
    print("    偏离重放具备在上游执行的前提条件（仍需真实构建与真机验证）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
